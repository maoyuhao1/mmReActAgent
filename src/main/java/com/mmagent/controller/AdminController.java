package com.mmagent.controller;

import com.mmagent.document.ConversationDocument;
import com.mmagent.document.SessionDocument;
import com.mmagent.document.StreamEventDocument;
import com.mmagent.repository.ConversationRepository;
import com.mmagent.repository.SessionRepository;
import com.mmagent.repository.StreamEventRepository;
import com.mmagent.service.ReactAgentService;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 运维管理接口
 *
 * <p>接口列表：
 * GET  /api/admin/stats                              - 聚合统计数据
 * GET  /api/admin/tools                              - 已注册工具列表（名称/描述/参数Schema）
 * GET  /api/admin/sessions                           - 所有会话列表
 * GET  /api/admin/sessions/{sessionId}/conversations - 会话所有对话（含 running/error）
 * GET  /api/admin/conversations/{id}/events          - 某对话的流式事件
 * GET  /api/admin/events/recent                      - 最近 100 条流式事件
 * DELETE /api/admin/sessions/{sessionId}             - 强制销毁会话（含内存清理）
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private final SessionRepository sessionRepo;
    private final ConversationRepository conversationRepo;
    private final StreamEventRepository streamEventRepo;
    private final ReactAgentService agentService;
    private final Toolkit toolkit;

    public AdminController(SessionRepository sessionRepo,
                           ConversationRepository conversationRepo,
                           StreamEventRepository streamEventRepo,
                           ReactAgentService agentService,
                           Toolkit toolkit) {
        this.sessionRepo = sessionRepo;
        this.conversationRepo = conversationRepo;
        this.streamEventRepo = streamEventRepo;
        this.agentService = agentService;
        this.toolkit = toolkit;
    }

    /**
     * 聚合统计数据
     */
    @GetMapping("/stats")
    public Mono<Map<String, Object>> stats() {
        Mono<Long> totalSessions = sessionRepo.count();
        Mono<Long> activeSessions = sessionRepo.findByStatus("active").count();
        Mono<Long> totalConversations = conversationRepo.count();
        Mono<Long> doneConversations = conversationRepo.findByStatus("done").count();
        Mono<Long> errorConversations = conversationRepo.findByStatus("error").count();
        Mono<Long> runningConversations = conversationRepo.findByStatus("running").count();
        Mono<Long> totalEvents = streamEventRepo.count();

        return Mono.zip(
                totalSessions, activeSessions,
                totalConversations, doneConversations, errorConversations, runningConversations,
                totalEvents
        ).map(t -> Map.of(
                "totalSessions", t.getT1(),
                "activeSessions", t.getT2(),
                "closedSessions", t.getT1() - t.getT2(),
                "totalConversations", t.getT3(),
                "doneConversations", t.getT4(),
                "errorConversations", t.getT5(),
                "runningConversations", t.getT6(),
                "totalEvents", t.getT7(),
                "inMemorySessions", agentService.getActiveSessionCount()
        ));
    }

    /**
     * 所有会话列表（按最后活跃时间倒序）
     */
    @GetMapping("/sessions")
    public Flux<SessionDocument> listSessions() {
        return sessionRepo.findAll()
                .sort((a, b) -> b.getLastActiveAt().compareTo(a.getLastActiveAt()));
    }

    /**
     * 某会话的所有对话记录（含 running / error 状态）
     */
    @GetMapping("/sessions/{sessionId}/conversations")
    public Flux<ConversationDocument> listConversations(@PathVariable String sessionId) {
        return conversationRepo.findBySessionIdOrderByTurnIndexAsc(sessionId);
    }

    /**
     * 所有对话记录（按开始时间倒序，最近 200 条）
     */
    @GetMapping("/conversations")
    public Flux<ConversationDocument> listAllConversations() {
        return conversationRepo.findAllByOrderByStartTimeDesc().take(200);
    }

    /**
     * 某对话的流式事件列表
     */
    @GetMapping("/conversations/{conversationId}/events")
    public Flux<StreamEventDocument> listEvents(@PathVariable String conversationId) {
        return streamEventRepo.findByConversationIdOrderBySequenceAsc(conversationId);
    }

    /**
     * 最近 100 条流式事件（跨所有对话）
     */
    @GetMapping("/events/recent")
    public Flux<StreamEventDocument> recentEvents() {
        return streamEventRepo.findTop100ByOrderByTimestampDesc();
    }

    /**
     * 已注册工具列表（名称、描述、参数 Schema）
     */
    @GetMapping("/tools")
    public Mono<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> tools = toolkit.getToolNames().stream()
                .sorted()
                .map(name -> {
                    AgentTool tool = toolkit.getTool(name);
                    return Map.<String, Object>of(
                            "name", tool.getName(),
                            "description", tool.getDescription(),
                            "parameters", tool.getParameters()
                    );
                })
                .toList();
        return Mono.just(tools);
    }

    /**
     * 强制销毁会话（清理内存 + 标记 MongoDB 状态）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Mono<Map<String, String>> forceDeleteSession(@PathVariable String sessionId) {
        agentService.removeSession(sessionId);
        return Mono.just(Map.of("message", "会话已强制销毁：" + sessionId));
    }
}
