package com.mmagent.config;

import com.mmagent.tools.MyTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    /**
     * 工具集 Bean
     * 注册所有自定义工具
     */
    @Bean
    public Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MyTools());
        return toolkit;
    }

    /**
     * ReAct Agent Bean
     * 原型模式：每次注入创建新实例（各自独立内存）
     * 这里使用单例 + 外部会话管理，由 ReactAgentService 负责多会话
     */
    @Bean
    public ReActAgent reactAgent(Toolkit toolkit) {
        return ReActAgent.builder()
                .name("ReActBot")
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
                .memory(new InMemoryMemory())
                .maxIters(maxIters)
                .build();
    }
}
