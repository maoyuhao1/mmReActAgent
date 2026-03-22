package com.mmagent.config;

import com.mmagent.tools.CalculateTool;
import com.mmagent.tools.GetCurrentTimeTool;
import com.mmagent.tools.UrlFetchTool;
import com.mmagent.tools.WebSearchTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
