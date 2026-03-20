package com.mmagent.service;

import com.mmagent.document.ConversationDocument;
import com.mmagent.document.ConversationDocument.ToolCallRecord;
import com.mmagent.document.SessionDocument;
import com.mmagent.document.StreamEventDocument;
import com.mmagent.repository.ConversationRepository;
import com.mmagent.repository.SessionRepository;
import com.mmagent.repository.StreamEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 对话持久化服务
 *
 * <p>所有写操作均返回 Mono，调用方通过 .subscribe() 异步触发，不阻塞 SSE 流。
 */
@Service
public class ConversationPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ConversationPersistenceService.class);

    private final SessionRepository sessionRepo;
    private final ConversationRepository conversationRepo;
    private final StreamEventRepository streamEventRepo;
    private final ReactiveMongoTemplate mongoTemplate;

    public ConversationPersistenceService(SessionRepository sessionRepo,
                                          ConversationRepository conversationRepo,
                                          StreamEventRepository streamEventRepo,
                                          ReactiveMongoTemplate mongoTemplate) {
        this.sessionRepo = sessionRepo;
        this.conversationRepo = conversationRepo;
        this.streamEventRepo = streamEventRepo;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 创建会话记录
     */
    public Mono<Void> createSession(String sessionId) {
        SessionDocument doc = SessionDocument.builder()
                .id(sessionId)
                .createdAt(Instant.now())
                .lastActiveAt(Instant.now())
                .status("active")
                .messageCount(0)
                .build();
        return sessionRepo.save(doc)
                .doOnSuccess(s -> log.debug("会话已持久化：{}", sessionId))
                .doOnError(e -> log.error("会话持久化失败 [sessionId={}]", sessionId, e))
                .then();
    }

    /**
     * 开始一轮对话，返回 ConversationDocument（含生成的 conversationId）
     */
    public Mono<ConversationDocument> startConversation(String sessionId, String userMessage, int turnIndex) {
        ConversationDocument doc = ConversationDocument.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .turnIndex(turnIndex)
                .userMessage(userMessage)
                .toolCalls(new ArrayList<>())
                .startTime(Instant.now())
                .status("running")
                .build();
        return conversationRepo.save(doc)
                .doOnSuccess(d -> log.debug("对话已创建 [conversationId={}, sessionId={}]", d.getId(), sessionId))
                .doOnError(e -> log.error("对话创建失败 [sessionId={}]", sessionId, e));
    }

    /**
     * 追加工具调用记录到对话文档（原子 $push，避免并发竞态）
     */
    public Mono<Void> appendToolCall(String conversationId, ToolCallRecord record) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId)),
                new Update().push("toolCalls", record),
                ConversationDocument.class
        ).doOnError(e -> log.error("工具调用记录追加失败 [conversationId={}]", conversationId, e))
         .then();
    }

    /**
     * 保存流式事件（tool_call / tool_result / error）
     */
    public Mono<Void> saveStreamEvent(StreamEventDocument event) {
        return streamEventRepo.save(event)
                .doOnError(e -> log.error("流式事件保存失败 [conversationId={}, type={}]",
                        event.getConversationId(), event.getEventType(), e))
                .then();
    }

    /**
     * 对话正常结束：原子 $set 写入完整 AI 回复
     */
    public Mono<Void> completeConversation(String conversationId, String aiReply, long durationMs) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId)),
                new Update().set("aiReply", aiReply)
                            .set("status", "done")
                            .set("endTime", Instant.now())
                            .set("durationMs", durationMs),
                ConversationDocument.class
        ).doOnSuccess(r -> log.debug("对话完成 [conversationId={}, durationMs={}]", conversationId, durationMs))
         .doOnError(e -> log.error("对话完成写入失败 [conversationId={}]", conversationId, e))
         .then();
    }

    /**
     * 对话异常结束：原子 $set 写入错误信息
     */
    public Mono<Void> failConversation(String conversationId, String errorMsg, long durationMs) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId)),
                new Update().set("status", "error")
                            .set("errorMsg", errorMsg)
                            .set("endTime", Instant.now())
                            .set("durationMs", durationMs),
                ConversationDocument.class
        ).doOnError(e -> log.error("对话异常写入失败 [conversationId={}]", conversationId, e))
         .then();
    }

    /**
     * 更新会话最后活跃时间并原子累加消息计数
     */
    public Mono<Void> touchSession(String sessionId) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("lastActiveAt", Instant.now()).inc("messageCount", 1),
                SessionDocument.class
        ).doOnError(e -> log.error("会话 touch 失败 [sessionId={}]", sessionId, e))
         .then();
    }

    /**
     * 查询会话的历史对话（仅已完成的轮次，按 turnIndex 升序）
     */
    public reactor.core.publisher.Flux<com.mmagent.document.ConversationDocument> getConversationHistory(String sessionId) {
        return conversationRepo.findBySessionIdOrderByTurnIndexAsc(sessionId)
                .filter(conv -> "done".equals(conv.getStatus()));
    }

    /**
     * 查询所有会话列表
     */
    public reactor.core.publisher.Flux<com.mmagent.document.SessionDocument> listSessions() {
        return sessionRepo.findAll();
    }

    /**
     * 关闭会话（原子 $set）
     */
    public Mono<Void> closeSession(String sessionId) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("status", "closed").set("lastActiveAt", Instant.now()),
                SessionDocument.class
        ).doOnError(e -> log.error("会话关闭失败 [sessionId={}]", sessionId, e))
         .then();
    }
}
