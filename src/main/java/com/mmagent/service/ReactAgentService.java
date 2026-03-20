package com.mmagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmagent.document.ConversationDocument.ToolCallRecord;
import com.mmagent.document.StreamEventDocument;
import com.mmagent.model.SseEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct Agent 服务层
 *
 * <p>核心职责：
 * 1. 多会话管理（每个 sessionId 对应独立 Agent 实例和内存）
 * 2. 流式输出：通过 Hook 捕获推理/行动事件，推送到 SSE 流
 * 3. 会话生命周期管理（含客户端断连取消）
 * 4. 对话数据持久化到 MongoDB，支持重启后从历史记录恢复上下文
 * 5. Redis Session 锁防并发覆盖
 * 6. Resilience4j 熔断保护 DashScope API 调用
 */
@Service
public class ReactAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个会话的 Agent 实例（每轮新建，但共享同一个 Memory） */
    private final Map<String, ReActAgent> sessionAgents = new ConcurrentHashMap<>();

    /**
     * 每个会话的 InMemoryMemory（跨轮次复用，应用重启后从 MongoDB 恢复）
     * key: sessionId
     */
    private final Map<String, InMemoryMemory> sessionMemories = new ConcurrentHashMap<>();

    /** 每个会话的对话轮次计数（从 MongoDB 中对话数初始化） */
    private final Map<String, AtomicInteger> sessionTurnCounters = new ConcurrentHashMap<>();

    /** key=conversationId, value=AI 回复缓冲区（每轮重置，用于对话结束后一次性持久化） */
    private final Map<String, StringBuilder> replyBuffers = new ConcurrentHashMap<>();

    /** key=sessionId, value=当前进行中对话的 conversationId */
    private final Map<String, String> activeConversations = new ConcurrentHashMap<>();

    /** key=conversationId, value=agent.call() 的 Disposable（用于客户端断连时取消） */
    private final Map<String, Disposable> activeDisposables = new ConcurrentHashMap<>();

    /** key=conversationId, value=心跳的 Disposable */
    private final Map<String, Disposable> heartbeatDisposables = new ConcurrentHashMap<>();

    @Value("${agent.dashscope.api-key}")
    private String apiKey;

    @Value("${agent.dashscope.model-name:qwen-max}")
    private String modelName;

    @Value("${agent.react.max-iters:10}")
    private int maxIters;

    @Value("${agent.react.sys-prompt}")
    private String sysPrompt;

    private final Toolkit toolkit;
    private final ConversationPersistenceService persistenceService;
    private final RateLimitService rateLimitService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ReactAgentService(Toolkit toolkit,
                             ConversationPersistenceService persistenceService,
                             RateLimitService rateLimitService,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.toolkit = toolkit;
        this.persistenceService = persistenceService;
        this.rateLimitService = rateLimitService;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * 流式对话接口（核心方法）
     *
     * <p>先获取 Redis Session 锁，防止同一会话并发请求互相覆盖；
     * Redis 不可用时降级放行（onErrorReturn(true)）。
     */
    public Flux<String> chatStream(String sessionId, String userMessage) {
        return rateLimitService.tryAcquireSessionLock(sessionId)
                .onErrorReturn(true)  // Redis 不可用时降级放行
                .flatMapMany(acquired -> {
                    if (!acquired) {
                        return Flux.just(serialize(SseEvent.error("当前会话正在处理中，请稍后再试", sessionId)));
                    }
                    return doChat(sessionId, userMessage)
                            .doFinally(signal -> rateLimitService.releaseSessionLock(sessionId).subscribe());
                });
    }

    private String serialize(SseEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"error\",\"content\":\"序列化失败\"}";
        }
    }

    /**
     * 实际对话逻辑（从 MongoDB 恢复历史 → 创建对话记录 → 启动心跳 → Agent 执行）
     */
    private Flux<String> doChat(String sessionId, String userMessage) {
        Mono<InMemoryMemory> memoryMono;
        if (sessionMemories.containsKey(sessionId)) {
            memoryMono = Mono.just(sessionMemories.get(sessionId));
        } else {
            memoryMono = loadHistoryFromMongo(sessionId);
        }

        long startMs = System.currentTimeMillis();

        return memoryMono.flatMapMany(memory -> {
            sessionMemories.put(sessionId, memory);

            // 初始化轮次计数（取 memory 中已有消息对数作为起始）
            int turnIndex = sessionTurnCounters
                    .computeIfAbsent(sessionId, k -> {
                        int historyTurns = memory.getMessages().size() / 2;
                        return new AtomicInteger(historyTurns);
                    })
                    .getAndIncrement();

            // 有界 Sink（512容量），防止无界堆积 OOM
            Sinks.Many<SseEvent> sink = Sinks.many().unicast()
                    .onBackpressureBuffer(new ArrayBlockingQueue<>(512));

            return persistenceService.startConversation(sessionId, userMessage, turnIndex)
                    .flatMapMany(conversationDoc -> {
                        String conversationId = conversationDoc.getId();

                        // 每轮重置回复缓冲区（key 为 conversationId，避免并发请求互覆）
                        replyBuffers.put(conversationId, new StringBuilder());
                        activeConversations.put(sessionId, conversationId);

                        // 启动心跳（15s 间隔，防止网关超时断连）
                        Disposable hb = Flux.interval(Duration.ofSeconds(15))
                                .subscribe(tick -> sink.tryEmitNext(SseEvent.heartbeat(sessionId)));
                        heartbeatDisposables.put(conversationId, hb);

                        ReActAgent agent = buildAgentWithHooks(sessionId, sink, conversationId, memory);
                        sessionAgents.put(sessionId, agent);

                        Msg userMsg = Msg.builder()
                                .textContent(userMessage)
                                .build();

                        // 熔断器包装 agent.call()
                        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("dashscope-agent");

                        Disposable disposable = agent.call(userMsg)
                                .transformDeferred(CircuitBreakerOperator.of(cb))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                        response -> {
                                            String aiReply = getAgentReplyBuffer(conversationId);
                                            long durationMs = System.currentTimeMillis() - startMs;
                                            persistenceService.completeConversation(conversationId, aiReply, durationMs).subscribe();
                                            persistenceService.touchSession(sessionId).subscribe();
                                            stopConversationResources(conversationId, sessionId);
                                            sink.tryEmitNext(SseEvent.done(sessionId));
                                            sink.tryEmitComplete();
                                        },
                                        error -> {
                                            long durationMs = System.currentTimeMillis() - startMs;
                                            if (error instanceof CallNotPermittedException) {
                                                sink.tryEmitNext(SseEvent.error("服务繁忙，熔断保护中，请稍后重试", sessionId));
                                                persistenceService.failConversation(conversationId, "circuit_breaker_open", 0).subscribe();
                                            } else {
                                                log.error("Agent 执行异常 [sessionId={}, conversationId={}]",
                                                        sessionId, conversationId, error);
                                                persistenceService.failConversation(conversationId, error.getMessage(), durationMs).subscribe();
                                                persistenceService.saveStreamEvent(StreamEventDocument.builder()
                                                        .id(UUID.randomUUID().toString())
                                                        .sessionId(sessionId)
                                                        .conversationId(conversationId)
                                                        .eventType("error")
                                                        .content(error.getMessage())
                                                        .timestamp(Instant.now())
                                                        .sequence(9999)
                                                        .build()).subscribe();
                                                sink.tryEmitNext(SseEvent.error(error.getMessage(), sessionId));
                                            }
                                            stopConversationResources(conversationId, sessionId);
                                            sink.tryEmitComplete();
                                        }
                                );
                        activeDisposables.put(conversationId, disposable);

                        return sink.asFlux()
                                .map(this::serialize);
                    });
        });
    }

    /**
     * 清理单轮对话资源（心跳、Disposable、replyBuffer、activeConversations 映射）
     */
    private void stopConversationResources(String conversationId, String sessionId) {
        activeConversations.remove(sessionId);
        activeDisposables.remove(conversationId);
        replyBuffers.remove(conversationId);
        Disposable hb = heartbeatDisposables.remove(conversationId);
        if (hb != null && !hb.isDisposed()) hb.dispose();
    }

    /**
     * 客户端断连时取消正在进行的 Agent 调用
     */
    public void cancelSession(String sessionId) {
        String conversationId = activeConversations.get(sessionId);
        if (conversationId != null) {
            Disposable d = activeDisposables.get(conversationId);
            if (d != null && !d.isDisposed()) d.dispose();
            stopConversationResources(conversationId, sessionId);
            persistenceService.failConversation(conversationId, "client_disconnected", 0).subscribe();
            rateLimitService.releaseSessionLock(sessionId).subscribe();
            log.info("[CANCEL] sessionId={} 客户端断连，Agent 已取消", sessionId);
        }
    }

    /**
     * 从 MongoDB 加载历史对话并还原到 InMemoryMemory
     * 仅加载 status=done 的轮次（已完整完成的对话）
     */
    private Mono<InMemoryMemory> loadHistoryFromMongo(String sessionId) {
        InMemoryMemory memory = new InMemoryMemory();
        return persistenceService.getConversationHistory(sessionId)
                .doOnNext(conv -> {
                    if (conv.getUserMessage() != null) {
                        memory.addMessage(Msg.builder()
                                .role(MsgRole.USER)
                                .textContent(conv.getUserMessage())
                                .build());
                    }
                    if (conv.getAiReply() != null) {
                        memory.addMessage(Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .textContent(conv.getAiReply())
                                .build());
                    }
                })
                .then(Mono.fromCallable(() -> {
                    int turns = memory.getMessages().size() / 2;
                    if (turns > 0) {
                        log.info("[HISTORY_RESTORED] sessionId={} 已从 MongoDB 恢复 {} 轮历史对话", sessionId, turns);
                    }
                    return memory;
                }));
    }

    private String getAgentReplyBuffer(String conversationId) {
        StringBuilder buf = replyBuffers.get(conversationId);
        return buf != null ? buf.toString() : "";
    }

    /**
     * 构建携带流式 Hook 的 Agent，使用传入的 Memory 实例
     */
    private ReActAgent buildAgentWithHooks(String sessionId, Sinks.Many<SseEvent> sink,
                                           String conversationId, InMemoryMemory memory) {
        AtomicInteger eventSeq = new AtomicInteger(0);
        AtomicLong toolCallStart = new AtomicLong(0);
        AtomicReference<String> currentToolName = new AtomicReference<>("");
        AtomicReference<String> currentToolArgs = new AtomicReference<>("");

        return ReActAgent.builder()
                .name("ReActBot-" + sessionId)
                .sysPrompt(sysPrompt)
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(modelName)
                                .stream(true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build()
                )
                .toolkit(toolkit)
                .memory(memory)  // 复用已有 Memory，保留历史上下文
                .maxIters(maxIters)
                .hook(new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent e) {
                            String chunk = e.getIncrementalChunk() != null
                                    ? e.getIncrementalChunk().getTextContent() : null;
                            if (chunk != null && !chunk.isEmpty()) {
                                // 使用 conversationId 作 key，避免并发请求互覆
                                StringBuilder buf = replyBuffers.get(conversationId);
                                if (buf != null) buf.append(chunk);
                                sink.tryEmitNext(SseEvent.chunk(chunk, sessionId));
                            }
                        } else if (event instanceof PreActingEvent e) {
                            String toolName = e.getToolUse().getName();
                            String toolArgs = e.getToolUse().getInput() != null
                                    ? e.getToolUse().getInput().toString() : "{}";
                            currentToolName.set(toolName);
                            currentToolArgs.set(toolArgs);
                            toolCallStart.set(System.currentTimeMillis());

                            String toolInfo = String.format("调用工具：%s，参数：%s", toolName, toolArgs);
                            sink.tryEmitNext(SseEvent.toolCall(toolInfo, sessionId));

                            log.info("[TOOL_CALL] sessionId={} conversationId={} tool={} args={}",
                                    sessionId, conversationId, toolName, toolArgs);

                            persistenceService.saveStreamEvent(StreamEventDocument.builder()
                                    .id(UUID.randomUUID().toString())
                                    .sessionId(sessionId)
                                    .conversationId(conversationId)
                                    .eventType("tool_call")
                                    .content(toolInfo)
                                    .timestamp(Instant.now())
                                    .sequence(eventSeq.getAndIncrement())
                                    .build()).subscribe();

                        } else if (event instanceof ActingChunkEvent e) {
                            String chunk = e.getChunk() != null
                                    ? e.getChunk().getOutput().toString() : null;
                            if (chunk != null && !chunk.isEmpty()) {
                                sink.tryEmitNext(SseEvent.toolResult(chunk, sessionId));
                            }
                        } else if (event instanceof PostActingEvent e) {
                            long toolDuration = System.currentTimeMillis() - toolCallStart.get();
                            String result = e.getToolResult() != null
                                    ? e.getToolResult().getOutput().toString() : "null";

                            String resultMsg = String.format("工具结果：%s", result);
                            sink.tryEmitNext(SseEvent.toolResult(resultMsg, sessionId));

                            log.info("[TOOL_RESULT] sessionId={} conversationId={} tool={} durationMs={} result={}",
                                    sessionId, conversationId, currentToolName.get(), toolDuration, result);

                            persistenceService.appendToolCall(conversationId, ToolCallRecord.builder()
                                    .toolName(currentToolName.get())
                                    .inputArgs(currentToolArgs.get())
                                    .result(result)
                                    .durationMs(toolDuration)
                                    .build()).subscribe();

                            persistenceService.saveStreamEvent(StreamEventDocument.builder()
                                    .id(UUID.randomUUID().toString())
                                    .sessionId(sessionId)
                                    .conversationId(conversationId)
                                    .eventType("tool_result")
                                    .content(resultMsg)
                                    .timestamp(Instant.now())
                                    .sequence(eventSeq.getAndIncrement())
                                    .build()).subscribe();
                        }
                        return Mono.just(event);
                    }
                })
                .build();
    }

    /**
     * 创建新会话，返回完整 UUID sessionId，并持久化到 MongoDB
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        // 预初始化空 Memory，避免第一次调用时触发无效的历史加载
        sessionMemories.put(sessionId, new InMemoryMemory());
        persistenceService.createSession(sessionId).subscribe();
        return sessionId;
    }

    /**
     * 清理会话（释放资源），并标记 MongoDB 中会话为 closed
     */
    public void removeSession(String sessionId) {
        sessionAgents.remove(sessionId);
        sessionMemories.remove(sessionId);
        sessionTurnCounters.remove(sessionId);
        persistenceService.closeSession(sessionId).subscribe();
        log.info("会话已清理：{}", sessionId);
    }

    /**
     * 获取当前活跃会话数
     */
    public int getActiveSessionCount() {
        return sessionMemories.size();
    }
}
