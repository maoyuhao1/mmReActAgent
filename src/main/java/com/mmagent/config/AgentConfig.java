package com.mmagent.config;

import com.mmagent.tools.CalculateTool;
import com.mmagent.tools.GetCurrentTimeTool;
import com.mmagent.tools.UrlFetchTool;
import com.mmagent.tools.WebSearchTool;
import com.mmagent.session.MongoSession;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.PromptConfig;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.Session;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AgentConfig {

    @Value("${agent.dashscope.api-key}")
    private String apiKey;

    @Value("${agent.dashscope.model-name:qwen-max}")
    private String modelName;

    @Value("${agent.react.max-iters:10}")
    private int maxIters;

    @Value("${agent.react.sys-prompt}")
    private String sysPrompt;

    @Value("${search.bocha.api-key:}")
    private String bochaApiKey;

    @Value("${search.bocha.max-results:5}")
    private int bochaMaxResults;

    @Value("${agent.memory.msg-threshold:60}")
    private int memoryMsgThreshold;

    @Value("${agent.memory.max-token:28000}")
    private long memoryMaxToken;

    @Value("${agent.memory.token-ratio:0.75}")
    private double memoryTokenRatio;

    @Value("${agent.memory.last-keep:20}")
    private int memoryLastKeep;

    /**
     * 通用 HTTP 客户端（供搜索工具和网页抓取工具复用）
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * 工具集 Bean：注册时间、计算、互联网搜索、网页抓取四类工具
     */
    @Bean
    public Toolkit toolkit(WebClient webClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new GetCurrentTimeTool());
        toolkit.registerTool(new CalculateTool());
        toolkit.registerTool(new WebSearchTool(webClient, bochaApiKey, bochaMaxResults));
        toolkit.registerTool(new UrlFetchTool(webClient));
        return toolkit;
    }

    /**
     * DashScopeChatModel Bean（单例），供 ReactAgentService 每轮复用，避免重复构建
     */
    @Bean
    public DashScopeChatModel dashScopeChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .enableThinking(false)
                .formatter(new DashScopeChatFormatter())
                .build();
    }

    /**
     * AutoContextConfig Bean：上下文自动压缩配置。
     * 禁用 offload（largePayloadThreshold=MAX），仅使用无需 ContextOffloadTool 的压缩策略。
     * 使用中文压缩提示词，适配中文对话场景。
     */
    @Bean
    public AutoContextConfig autoContextConfig() {
        PromptConfig promptConfig = PromptConfig.builder()
                // 策略1：压缩历史工具调用记录
                .previousRoundToolCompressPrompt(
                        "你是专业的内容压缩专家，任务是对以下工具调用历史进行智能压缩和摘要：\n"
                        + "    - 保留：工具名称、精确参数（含参数值）、输出结果的简洁事实摘要。\n"
                        + "    - 对同一工具的重复调用：\n"
                        + "        • 将参数相同、结果相同的重复调用合并为一条，并注明调用次数。\n"
                        + "        • 仅列出产生不同结果的不同参数组合。\n"
                        + "        • 如行为不变，可省略非关键的变化参数（如时间戳、请求ID）。\n"
                        + "    - 若工具名称或输出暗示有副作用（如含\"写入\"、\"更新\"、\"删除\"、\"创建\"，或返回\"已写入\"等确认信息），\n"
                        + "      视为写操作，须保留关键细节：文件路径、数据键、内容片段、状态变更、成功/失败标记。\n"
                        + "    - 输出必须为纯文本，不使用 Markdown、JSON、列表、标题或元注释。\n"
                        + "    - 若工具输出疑似被截断或损坏，附注\"[已截断]\"。"
                )
                // 策略4：压缩历史对话轮次为摘要
                .previousRoundSummaryPrompt(
                        "你是专业的对话压缩专家，服务于自主 Agent 系统。\n"
                        + "任务：将上一轮对话中助手的最终回复改写为一条自包含、简洁的回复，\n"
                        + "融入该轮所有关键事实，不得提及工具、函数或内部执行步骤。\n"
                        + "\n"
                        + "输入包含：用户的原始问题、助手的原始回复，以及支撑该回复的工具执行结果。\n"
                        + "\n"
                        + "你的输出将替换原助手消息，形成简洁的\"用户->助手\"对话对供后续上下文使用。\n"
                        + "\n"
                        + "要求：\n"
                        + "  - 绝不提及工具、函数、API 调用或执行步骤（不使用\"我调用了...\"、\"系统返回...\"等表述）。\n"
                        + "  - 将所有发现以助手直接掌握的客观知识表述出来。\n"
                        + "  - 务必保留工具结果中的关键事实，尤其是：\n"
                        + "      • 文件路径及其内容、变更或创建情况\n"
                        + "      • 具有诊断价值的精确错误信息\n"
                        + "      • ID、URL、端口、状态码、配置值、数据键\n"
                        + "      • 写操作/变更操作的结果（写入了什么、写到了哪里）\n"
                        + "      • 服务状态或进程信息\n"
                        + "  - 若执行了某项操作，明确说明变更了什么、在哪里变更。\n"
                        + "  - 若操作失败或不完整，说明具体限制原因。\n"
                        + "  - 合并冗余信息，省略无实际价值的通用成功提示。\n"
                        + "  - 语言清晰、信息充分，避免\"根据日志...\"、\"如观察到的...\"等元描述。\n"
                        + "  - 输出必须为纯文本，不使用 Markdown、列表、JSON、XML 或章节标题。"
                )
                // 策略5：压缩当前轮次中的大消息
                .currentRoundLargeMessagePrompt(
                        "你是专业的内容压缩专家，任务是对以下消息内容进行智能摘要。\n"
                        + "该消息超过了大小阈值，需要在保留所有关键信息的前提下进行压缩。\n"
                        + "\n"
                        + "重要提示：这是当前轮次的内容，压缩时务必格外谨慎和保守，\n"
                        + "尽可能多地保留信息，因为这些信息正在当前对话中被使用。\n"
                        + "\n"
                        + "请提供简洁摘要，要求：\n"
                        + "    - 保留所有关键信息和重要细节\n"
                        + "    - 维护后续参考所需的重要上下文\n"
                        + "    - 突出重要的操作结果、执行结果或状态信息\n"
                        + "    - 如含有工具调用信息，保留工具名称、调用ID、关键参数"
                )
                // 策略6：整合当前轮次工具执行结果
                .currentRoundCompressPrompt(
                        "你是自主 Agent 系统的专业上下文整合专家，\n"
                        + "任务是将新的工具执行结果整合进当前对话上下文。\n"
                        + "\n"
                        + "输入结构：\n"
                        + "- 输入由以下部分组成：\n"
                        + "  (a) 可选：以 <!-- CONTEXT_OFFLOAD: uuid=... --> 结尾的先前压缩上下文块\n"
                        + "  (b) 其后跟零个或多个当前轮次的交替 tool_use 和 tool_result 消息\n"
                        + "- 输入中不包含用户消息。\n"
                        + "- 与计划相关的工具已在上游过滤。\n"
                        + "\n"
                        + "处理流程：\n"
                        + "1. 若输入包含匹配 <!-- CONTEXT_OFFLOAD: uuid=... --> 的行：\n"
                        + "   - 原样保留该行之前的所有文本作为先前上下文。\n"
                        + "   - 仅处理该行之后的 tool_use/tool_result 对。\n"
                        + "2. 否则（未找到 offload 标记）：\n"
                        + "   - 将整个输入视为首次压缩轮次的新工具交互。\n"
                        + "   - 仅根据这些工具调用及其结果生成摘要。\n"
                        + "3. 对每个 tool_use/tool_result 对：\n"
                        + "   - 以第一人称陈述句进行摘要：\n"
                        + "     \"我调用了 [工具名]，参数为 [参数1=值1, ...]；返回结果：[关键细节]。\"\n"
                        + "   - 保留所有技术细节：文件路径、ID、错误码、配置值、状态变更。\n"
                        + "   - 若结果被截断或格式异常，原样保留并添加前缀 [未解析输出]。\n"
                        + "\n"
                        + "输出要求：\n"
                        + "- 单段纯文本，格式为：[先前上下文（如有）]\\n[新工具摘要]\n"
                        + "- 输出中不得包含任何 <!-- CONTEXT_OFFLOAD --> 标记。\n"
                        + "- 不得提及用户请求、意图或问题（输入中不含这些内容）。\n"
                        + "- 不使用 Markdown、JSON、列表，不使用\"如前所述\"、\"新操作：\"等短语。\n"
                        + "- 输出将作为新的压缩上下文使用，新的 offload 标记将由外部附加。\n"
                        + "\n"
                        + "可从 tool_result 中删除的内容：\n"
                        + "- 样板文本（许可证、自动注释）\n"
                        + "- 无实际价值的冗余成功信息\n"
                        + "- 重复的日志前缀（核心内容已保留时）\n"
                        + "\n"
                        + "严格禁止：\n"
                        + "- 包含原始 tool_use/tool_result JSON\n"
                        + "- 重新压缩或修改先前上下文\n"
                        + "- 添加任何 offload 标记（新旧均不可）"
                )
                .build();

        return AutoContextConfig.builder()
                .msgThreshold(memoryMsgThreshold)
                .maxToken(memoryMaxToken)
                .tokenRatio(memoryTokenRatio)
                .lastKeep(memoryLastKeep)
                .largePayloadThreshold(Long.MAX_VALUE)  // 禁用 offload，避免需要 per-session Toolkit
                .customPrompt(promptConfig)
                .build();
    }

    /**
     * AgentScope Session Bean：使用 MongoDB 持久化 AutoContextMemory（含压缩后完整消息历史）。
     * 存储结构参照 AgentScope MySQL 实现，每条消息按行存入 agentscope_sessions 集合。
     * 应用重启后可从 MongoDB 精准恢复对话上下文，无需依赖 Redis 作为持久化存储。
     */
    @Bean
    public Session agentSession(ReactiveMongoTemplate reactiveMongoTemplate) {
        return new MongoSession(reactiveMongoTemplate);
    }

    /**
     * ReAct Agent Bean
     * 原型模式：每次注入创建新实例（各自独立内存）
     * 这里使用单例 + 外部会话管理，由 ReactAgentService 负责多会话
     */
    @Bean
    public ReActAgent reactAgent(Toolkit toolkit, DashScopeChatModel dashScopeChatModel) {
        return ReActAgent.builder()
                .name("ReActBot")
                .sysPrompt(sysPrompt)
                .model(dashScopeChatModel)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(maxIters)
                .build();
    }
}
