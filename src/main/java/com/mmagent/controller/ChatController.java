package com.mmagent.controller;

import com.mmagent.document.ConversationDocument;
import com.mmagent.document.SessionDocument;
import com.mmagent.model.ChatRequest;
import com.mmagent.service.ConversationPersistenceService;
import com.mmagent.service.ReactAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * ReAct Agent 对话控制器
 *
 * <p>接口列表：
 * POST /api/chat/stream  - 流式对话（SSE）
 * POST /api/chat/session - 创建新会话
 * DELETE /api/chat/session/{id} - 销毁会话
 * GET  /api/chat/health  - 健康检查
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ReactAgentService agentService;
    private final ConversationPersistenceService persistenceService;

    public ChatController(ReactAgentService agentService, ConversationPersistenceService persistenceService) {
        this.agentService = agentService;
        this.persistenceService = persistenceService;
    }

    /**
     * 流式对话接口（核心接口）
     *
     * <p>使用 SSE（Server-Sent Events）推送流式响应：
     * - type=chunk      : LLM 推理过程的 token 流
     * - type=tool_call  : Agent 调用工具的信息
     * - type=tool_result: 工具执行结果
     * - type=done       : 本轮对话完成
     * - type=error      : 发生错误
     *
     * <p>示例请求：
     * curl -X POST http://localhost:8080/api/chat/stream \
     *   -H "Content-Type: application/json" \
     *   -d '{"message":"现在上海几点了？再帮我算一下 123 * 456", "sessionId":"abc123"}'
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        // sessionId 为空则自动创建
        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : agentService.createSession();

        String msgSummary = request.getMessage() != null && request.getMessage().length() > 50
                ? request.getMessage().substring(0, 50) + "..."
                : request.getMessage();
        log.info("[TURN_START] sessionId={} messageSummary=\"{}\"", sessionId, msgSummary);

        return agentService.chatStream(sessionId, request.getMessage())
                .map(json -> ServerSentEvent.<String>builder()
                        .id(sessionId)
                        .event("message")
                        .data(json)
                        .build())
                .doOnCancel(() -> {
                    log.info("[SSE_CANCEL] 客户端断连 sessionId={}", sessionId);
                    agentService.cancelSession(sessionId);
                })
                .doOnError(e -> log.error("SSE 流异常 [sessionId={}]", sessionId, e))
                .onErrorResume(e -> Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("error")
                                .data("{\"type\":\"error\",\"content\":\"" + e.getMessage() + "\"}")
                                .build()
                ));
    }

    /**
     * 创建新会话
     *
     * <p>示例：
     * curl -X POST http://localhost:8080/api/chat/session
     * → {"sessionId": "a1b2c3d4"}
     */
    @PostMapping("/session")
    public Map<String, String> createSession() {
        String sessionId = agentService.createSession();
        log.info("[SESSION_CREATED] sessionId={}", sessionId);
        return Map.of("sessionId", sessionId);
    }

    /**
     * 销毁会话（清理内存）
     */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        agentService.removeSession(sessionId);
        log.info("[SESSION_CLOSED] sessionId={}", sessionId);
        return Map.of("message", "会话已销毁：" + sessionId);
    }

    /**
     * 查询所有会话列表
     *
     * <p>示例：
     * curl http://localhost:8080/api/chat/sessions
     */
    @GetMapping("/sessions")
    public Flux<SessionDocument> listSessions() {
        return persistenceService.listSessions();
    }

    /**
     * 查询某个会话的历史对话列表（仅已完成轮次）
     *
     * <p>示例：
     * curl http://localhost:8080/api/chat/sessions/abc123/conversations
     */
    @GetMapping("/sessions/{sessionId}/conversations")
    public Flux<ConversationDocument> listConversations(@PathVariable String sessionId) {
        return persistenceService.getConversationHistory(sessionId);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "activeSessions", agentService.getActiveSessionCount()
        );
    }
}
