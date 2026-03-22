package com.mmagent.controller;

import com.mmagent.document.ConversationDocument;
import com.mmagent.document.SessionDocument;
import com.mmagent.service.ConversationPersistenceService;
import com.mmagent.service.ReactAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 会话管理控制器
 *
 * POST   /api/sessions             - 创建新会话
 * DELETE /api/sessions/{id}        - 销毁会话
 * GET    /api/sessions             - 查询所有会话
 * GET    /api/sessions/{id}/conversations  - 查询会话历史对话
 * PATCH  /api/sessions/{id}/title  - 更新会话标题
 * PATCH  /api/sessions/{id}/favorite - 切换收藏状态
 * GET    /api/sessions/health      - 健康检查
 */
@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "*")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final ReactAgentService agentService;
    private final ConversationPersistenceService persistenceService;

    public SessionController(ReactAgentService agentService,
                             ConversationPersistenceService persistenceService) {
        this.agentService = agentService;
        this.persistenceService = persistenceService;
    }

    /** 创建新会话 */
    @PostMapping
    public Map<String, String> createSession() {
        String sessionId = agentService.createSession();
        log.info("[SESSION_CREATED] sessionId={}", sessionId);
        return Map.of("sessionId", sessionId);
    }

    /** 销毁会话（清理内存） */
    @DeleteMapping("/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        agentService.removeSession(sessionId);
        log.info("[SESSION_CLOSED] sessionId={}", sessionId);
        return Map.of("message", "会话已销毁：" + sessionId);
    }

    /** 查询所有会话列表 */
    @GetMapping
    public Flux<SessionDocument> listSessions() {
        return persistenceService.listSessions();
    }

    /** 查询某个会话的历史对话（仅已完成轮次） */
    @GetMapping("/{sessionId}/conversations")
    public Flux<ConversationDocument> listConversations(@PathVariable String sessionId) {
        return persistenceService.getConversationHistory(sessionId);
    }

    /** 更新会话标题 */
    @PatchMapping("/{sessionId}/title")
    public Mono<Map<String, String>> updateTitle(@PathVariable String sessionId,
                                                  @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "").trim();
        return persistenceService.updateSessionTitle(sessionId, title)
                .thenReturn(Map.of("message", "标题已更新"));
    }

    /** 切换收藏状态 */
    @PatchMapping("/{sessionId}/favorite")
    public Mono<Map<String, Object>> toggleFavorite(@PathVariable String sessionId) {
        return persistenceService.toggleSessionFavorite(sessionId);
    }

    /** 健康检查 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "activeSessions", agentService.getActiveSessionCount()
        );
    }
}
