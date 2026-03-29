package com.mmagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmagent.document.ConversationDocument;
import com.mmagent.document.ConversationDocument.ToolCallRecord;
import com.mmagent.document.StreamEventDocument;
import com.mmagent.model.SseEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    /** 专用模型请求日志，写入 logs/model-request.log，格式含 sessionId */
    private static final Logger modelReqLog = LoggerFactory.getLogger("model-request");

    // 修复：通过构造函数注入 Spring 托管的 ObjectMapper（含 JSR-310 支持），避免手动 new
    private final ObjectMapper objectMapper;

    /**
     * 每个会话的 AutoContextMemory（跨轮次复用，含自动压缩；应用重启后从 Redis 恢复）
     * key: sessionId
     */
    private final Map<String, AutoContextMemory> sessionMemories = new ConcurrentHashMap<>();

    /** 每个会话的对话轮次计数（从 MongoDB 中对话数初始化） */
    private final Map<String, AtomicInteger> sessionTurnCounters = new ConcurrentHashMap<>();

    // 修复：replyBuffers 值类型改为 StringBuffer（线程安全），原 StringBuilder 非线程安全
    /** key=conversationId, value=AI 回复缓冲区（每轮重置，用于对话结束后一次性持久化） */
    private final Map<String, StringBuffer> replyBuffers = new ConcurrentHashMap<>();

    /** key=sessionId, value=当前进行中对话的 conversationId */
    private final Map<String, String> activeConversations = new ConcurrentHashMap<>();

    /** key=conversationId, value=agent.call() 的 Disposable（用于客户端断连时取消） */
    private final Map<String, Disposable> activeDisposables = new ConcurrentHashMap<>();

    /** key=conversationId, value=心跳的 Disposable */
    private final Map<String, Disposable> heartbeatDisposables = new ConcurrentHashMap<>();

    @Value("${agent.react.max-iters:10}")
    private int maxIters;

    @Value("${agent.react.sys-prompt}")
    private String sysPrompt;

    private final Toolkit toolkit;
    // 修复：注入 Spring 托管的单例 DashScopeChatModel，避免每次请求重新构建
    private final DashScopeChatModel dashScopeChatModel;
    private final ConversationPersistenceService persistenceService;
    private final RateLimitService rateLimitService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final TitleGeneratorService titleGeneratorService;
    /** AgentScope Session：使用 MongoSession 持久化 AutoContextMemory（含压缩后消息） */
    private final Session agentSession;
    /** 上下文压缩配置（消息数阈值、token 阈值等） */
    private final AutoContextConfig autoContextConfig;

    public ReactAgentService(Toolkit toolkit,
                             DashScopeChatModel dashScopeChatModel,
                             ConversationPersistenceService persistenceService,
                             RateLimitService rateLimitService,
                             CircuitBreakerRegistry circuitBreakerRegistry,
                             TitleGeneratorService titleGeneratorService,
                             ObjectMapper objectMapper,
                             Session agentSession,
                             AutoContextConfig autoContextConfig) {
        this.toolkit = toolkit;
        this.dashScopeChatModel = dashScopeChatModel;
        this.persistenceService = persistenceService;
        this.rateLimitService = rateLimitService;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.titleGeneratorService = titleGeneratorService;
        this.objectMapper = objectMapper;
        this.agentSession = agentSession;
        this.autoContextConfig = autoContextConfig;
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
        Mono<AutoContextMemory> memoryMono;
        if (sessionMemories.containsKey(sessionId)) {
            memoryMono = Mono.just(sessionMemories.get(sessionId));
        } else {
            memoryMono = loadMemory(sessionId);
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
                        replyBuffers.put(conversationId, new StringBuffer());
                        activeConversations.put(sessionId, conversationId);

                        // 启动心跳（15s 间隔，防止网关超时断连）
                        Disposable hb = Flux.interval(Duration.ofSeconds(15))
                                .subscribe(tick -> emitNext(sink, SseEvent.heartbeat(sessionId)));
                        heartbeatDisposables.put(conversationId, hb);

                        ReActAgent agent = buildAgentWithHooks(sessionId, sink, conversationId, memory);

                        Msg userMsg = Msg.builder()
                                .textContent(userMessage)
                                .build();

                        // 熔断器包装 agent.call()
                        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("dashscope-agent");

                        // 修复：先将占位 Disposable 放入 map，再 subscribe，避免竞态条件
                        // subscribeOn(boundedElastic) 保证 Agent 在线程池中执行，subscribe() 立即返回，
                        // 但提前占位使 cancelSession 在任意时刻都能安全获取引用
                        AtomicReference<Disposable> dispRef = new AtomicReference<>();
                        activeDisposables.put(conversationId, new Disposable() {
                            @Override
                            public void dispose() {
                                Disposable d = dispRef.get();
                                if (d != null) d.dispose();
                            }

                            @Override
                            public boolean isDisposed() {
                                Disposable d = dispRef.get();
                                return d != null && d.isDisposed();
                            }
                        });

                        Disposable disposable = agent.call(userMsg)
                                .transformDeferred(CircuitBreakerOperator.of(cb))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                        response -> {
                                            String aiReply = getAgentReplyBuffer(conversationId);
                                            long durationMs = System.currentTimeMillis() - startMs;
                                            persistenceService.completeConversation(conversationId, aiReply, durationMs).subscribe();
                                            persistenceService.touchSession(sessionId).subscribe();
                                            // 将完整 Memory（含工具调用 Msg 块）持久化到 Redis（AgentScope Session）
                                            saveMemoryToSession(sessionId, memory);
                                            // 第一轮对话结束后异步生成标题
                                            if (turnIndex == 0) {
                                                titleGeneratorService.generateAndSave(sessionId, userMessage);
                                            }
                                            stopConversationResources(conversationId, sessionId);
                                            emitNext(sink, SseEvent.done(sessionId));
                                            sink.tryEmitComplete();
                                        },
                                        error -> {
                                            long durationMs = System.currentTimeMillis() - startMs;
                                            if (error instanceof CallNotPermittedException) {
                                                emitNext(sink, SseEvent.error("服务繁忙，熔断保护中，请稍后重试", sessionId));
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
                                                emitNext(sink, SseEvent.error(error.getMessage(), sessionId));
                                            }
                                            stopConversationResources(conversationId, sessionId);
                                            sink.tryEmitComplete();
                                        }
                                );
                        dispRef.set(disposable);

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
     * 加载会话内存：优先从 Redis（AgentScope Session）恢复 AutoContextMemory；
     * Redis 无数据或消息为空时 fallback 到 MongoDB 纯文本历史重建。
     */
    private Mono<AutoContextMemory> loadMemory(String sessionId) {
        return Mono.fromCallable(() -> {
            AutoContextMemory memory = new AutoContextMemory(autoContextConfig, dashScopeChatModel);
            // loadIfExists 返回 true 但 workingMessages 可能为空（旧 InMemoryMemory key 遗留）
            if (memory.loadIfExists(agentSession, sessionId) && !memory.getMessages().isEmpty()) {
                log.info("[MEMORY_RESTORED_MONGO] sessionId={} 已从 MongoDB 恢复 {} 条消息（含压缩历史）",
                        sessionId, memory.getMessages().size());
                return memory;
            }
            return null; // null → empty Mono → 触发 fallback
        }).subscribeOn(Schedulers.boundedElastic())
        .switchIfEmpty(Mono.defer(() -> loadHistoryFromMongo(sessionId)));
    }

    /**
     * 将 AutoContextMemory 持久化到 MongoDB（同时保存 workingMessages 和 originalMessages）。
     * 在 boundedElastic 异步执行，不阻塞 SSE 流。
     */
    private void saveMemoryToSession(String sessionId, AutoContextMemory memory) {
        Mono.fromRunnable(() -> {
            memory.saveTo(agentSession, sessionId);
            log.debug("[MEMORY_SAVED_MONGO] sessionId={} Memory 已持久化，working={} original={}",
                    sessionId, memory.getMessages().size(), memory.getOriginalMemoryMsgs().size());
        }).subscribeOn(Schedulers.boundedElastic())
        .subscribe(null, e -> log.error("[MEMORY_SAVE_FAIL] sessionId={} Memory 持久化失败", sessionId, e));
    }

    /**
     * Fallback：从 MongoDB 加载历史对话并还原到 AutoContextMemory。
     * 每轮按 USER → (ASSISTANT tool_use + TOOL tool_result)* → ASSISTANT 顺序重建消息链。
     * 仅在 Redis 中不存在有效会话数据时触发。
     */
    private Mono<AutoContextMemory> loadHistoryFromMongo(String sessionId) {
        AutoContextMemory memory = new AutoContextMemory(autoContextConfig, dashScopeChatModel);
        return persistenceService.getConversationHistory(sessionId)
                .doOnNext(conv -> {
                    if (conv.getUserMessage() != null) {
                        memory.addMessage(Msg.builder()
                                .role(MsgRole.USER)
                                .textContent(conv.getUserMessage())
                                .build());
                    }
                    // 恢复工具调用消息块
                    if (conv.getToolCalls() != null) {
                        for (ConversationDocument.ToolCallRecord tc : conv.getToolCalls()) {
                            String toolCallId = UUID.randomUUID().toString();
                            // ASSISTANT: tool_use block
                            ToolUseBlock toolUseBlock = ToolUseBlock.builder()
                                    .id(toolCallId)
                                    .name(tc.getToolName())
                                    .input(parseInputArgs(tc.getInputArgs()))
                                    .build();
                            memory.addMessage(Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(toolUseBlock)
                                    .build());
                            // TOOL: tool_result block
                            String resultText = tc.getResult() != null ? tc.getResult() : "";
                            ToolResultBlock toolResultBlock = ToolResultBlock.of(
                                    toolCallId, tc.getToolName(),
                                    TextBlock.builder().text(resultText).build());
                            memory.addMessage(Msg.builder()
                                    .role(MsgRole.TOOL)
                                    .content(toolResultBlock)
                                    .build());
                        }
                    }
                    if (conv.getAiReply() != null) {
                        memory.addMessage(Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .textContent(conv.getAiReply())
                                .build());
                    }
                })
                .then(Mono.fromCallable(() -> {
                    if (!memory.getMessages().isEmpty()) {
                        log.info("[MEMORY_RESTORED_MONGO] sessionId={} 已从 MongoDB 恢复 {} 条消息（含工具调用块，fallback）",
                                sessionId, memory.getMessages().size());
                    }
                    return memory;
                }));
    }

    /**
     * 将工具调用参数 JSON 字符串解析为 Map，解析失败时降级为 raw 字符串包装。
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> parseInputArgs(String inputArgs) {
        if (inputArgs == null || inputArgs.isBlank()) return java.util.Map.of();
        try {
            return objectMapper.readValue(inputArgs, java.util.Map.class);
        } catch (Exception e) {
            log.warn("[TOOL_ARGS_PARSE_FAIL] 工具参数解析失败，降级为 raw 包装: {}", inputArgs);
            return java.util.Map.of("raw", inputArgs);
        }
    }

    private String getAgentReplyBuffer(String conversationId) {
        StringBuffer buf = replyBuffers.get(conversationId);
        return buf != null ? buf.toString() : "";
    }

    /**
     * 安全推送 SSE 事件到 Sink，失败时记录 WARN 日志而非静默丢弃
     */
    private void emitNext(Sinks.Many<SseEvent> sink, SseEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result != Sinks.EmitResult.OK) {
            log.warn("[SINK_EMIT_FAIL] result={} eventType={}", result, event.getType());
        }
    }

    /**
     * 构建携带流式 Hook 的 Agent，使用传入的 AutoContextMemory 实例
     */
    private ReActAgent buildAgentWithHooks(String sessionId, Sinks.Many<SseEvent> sink,
                                           String conversationId, AutoContextMemory memory) {
        AtomicInteger eventSeq = new AtomicInteger(0);
        AtomicLong toolCallStart = new AtomicLong(0);
        AtomicReference<String> currentToolName = new AtomicReference<>("");
        AtomicReference<String> currentToolArgs = new AtomicReference<>("");

        return ReActAgent.builder()
                .name("ReActBot-" + sessionId)
                .sysPrompt(sysPrompt)
                .model(dashScopeChatModel)  // 复用 Spring 单例，避免每轮重新构建
                .toolkit(toolkit)
                .memory(memory)  // 复用已有 Memory，保留历史上下文
                .maxIters(maxIters)
                .hook(new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent e) {
                            // 1. 压缩检查：超过阈值时压缩 workingMemoryStorage
                            boolean compressed = memory.compressIfNeeded();
                            if (compressed) {
                                log.info("[MEMORY_COMPRESSED] sessionId={} 触发上下文压缩，压缩后消息数={}",
                                        sessionId, memory.getMessages().size());
                            }
                            // 2. 用压缩后的消息更新本轮推理输入（保留 system prompt）
                            java.util.List<Msg> newInputMessages = new java.util.ArrayList<>();
                            java.util.List<Msg> original = e.getInputMessages();
                            if (!original.isEmpty() && original.get(0).getRole() == MsgRole.SYSTEM) {
                                newInputMessages.add(original.get(0));
                            }
                            newInputMessages.addAll(memory.getMessages());
                            e.setInputMessages(newInputMessages);
                            // 3. 记录实际发送给模型的消息（压缩后）
                            if (modelReqLog.isDebugEnabled()) {
                                MDC.put("sessionId", sessionId);
                                MDC.put("conversationId", conversationId);
                                try {
                                    String messagesJson = objectMapper.writerWithDefaultPrettyPrinter()
                                            .writeValueAsString(e.getInputMessages());
                                    modelReqLog.debug("model={} msgCount={} compressed={}\n{}",
                                            e.getModelName(),
                                            e.getInputMessages().size(),
                                            compressed,
                                            messagesJson);
                                } catch (JsonProcessingException ex) {
                                    modelReqLog.debug("序列化消息失败", ex);
                                } finally {
                                    MDC.remove("sessionId");
                                    MDC.remove("conversationId");
                                }
                            }
                        } else if (event instanceof ReasoningChunkEvent e) {
                            String chunk = e.getIncrementalChunk() != null
                                    ? e.getIncrementalChunk().getTextContent() : null;
                            if (chunk != null && !chunk.isEmpty()) {
                                // 使用 conversationId 作 key，避免并发请求互覆
                                StringBuffer buf = replyBuffers.get(conversationId);
                                if (buf != null) buf.append(chunk);
                                emitNext(sink, SseEvent.chunk(chunk, sessionId));
                            }
                        } else if (event instanceof PreActingEvent e) {
                            String toolName = e.getToolUse().getName();
                            String toolArgs = e.getToolUse().getInput() != null
                                    ? e.getToolUse().getInput().toString() : "{}";
                            currentToolName.set(toolName);
                            currentToolArgs.set(toolArgs);
                            toolCallStart.set(System.currentTimeMillis());

                            String toolInfo = String.format("调用工具：%s，参数：%s", toolName, toolArgs);
                            emitNext(sink, SseEvent.toolCall(toolInfo, sessionId));

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

                        } else if (event instanceof ActingChunkEvent) {
                            // 工具执行中间 chunk，不推送至前端，避免与 PostActingEvent 重复
                        } else if (event instanceof PostActingEvent e) {
                            long toolDuration = System.currentTimeMillis() - toolCallStart.get();
                            String result = e.getToolResult() != null
                                    ? e.getToolResult().getOutput().toString() : "null";

                            String resultMsg = String.format("工具结果：%s", result);
                            emitNext(sink, SseEvent.toolResult(resultMsg, sessionId));

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
        // 预初始化空 AutoContextMemory，避免第一次调用时触发无效的历史加载
        sessionMemories.put(sessionId, new AutoContextMemory(autoContextConfig, dashScopeChatModel));
        persistenceService.createSession(sessionId).subscribe();
        return sessionId;
    }

    /**
     * 清理会话（释放资源），并标记 MongoDB 中会话为 closed，同时删除 Redis 中的 AgentScope Session
     */
    public void removeSession(String sessionId) {
        // 修复：先终止活跃对话，清理心跳/Disposable/replyBuffer，再释放会话级资源
        cancelSession(sessionId);
        sessionMemories.remove(sessionId);
        sessionTurnCounters.remove(sessionId);
        persistenceService.closeSession(sessionId).subscribe();
        // 删除 Redis 中的 AgentScope Session 持久化数据
        Mono.fromRunnable(() -> agentSession.delete(SimpleSessionKey.of(sessionId)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> log.warn("[SESSION_DELETE_FAIL] sessionId={}", sessionId, e));
        log.info("会话已清理：{}", sessionId);
    }

    /**
     * 获取当前活跃会话数
     */
    public int getActiveSessionCount() {
        return sessionMemories.size();
    }
}
