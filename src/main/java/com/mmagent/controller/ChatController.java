package com.mmagent.controller;

import com.mmagent.model.ChatRequest;
import com.mmagent.service.ReactAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 对话流控制器
 *
 * POST /api/chat/stream - 流式对话（SSE）
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ReactAgentService agentService;

    public ChatController(ReactAgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 流式对话（SSE）
     *
     * <p>事件类型：chunk / tool_call / tool_result / done / error / heartbeat
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
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
}
