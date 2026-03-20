package com.mmagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ReAct Agent 示例工具集
 * 演示三种常用工具：时间查询、数学计算、知识搜索
 */
public class MyTools {

    /**
     * 获取指定时区的当前时间
     */
    @Tool(name = "get_current_time", description = "获取指定时区的当前时间")
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "时区名称，例如 'Asia/Shanghai'、'America/New_York'、'Europe/London'")
            String timezone) {
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            LocalDateTime now = LocalDateTime.now(zoneId);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return String.format("当前时间（%s）：%s", timezone, now.format(formatter));
        } catch (Exception e) {
            return "错误：无效的时区名称，请使用如 'Asia/Shanghai' 的格式";
        }
    }

    /**
     * 计算数学表达式（支持 +、-、*、/）
     */
    @Tool(name = "calculate", description = "计算数学表达式，支持加减乘除运算")
    public String calculate(
            @ToolParam(name = "expression", description = "数学表达式，例如 '100 + 200'、'36 * 48'、'1000 / 4'")
            String expression) {
        try {
            String expr = expression.replaceAll("\\s+", "");
            double result;

            if (expr.contains("+")) {
                String[] parts = expr.split("\\+", 2);
                result = Double.parseDouble(parts[0]) + Double.parseDouble(parts[1]);
            } else if (expr.matches(".*[^e]-.*")) {
                // 避免科学计数法中的负号误匹配
                int idx = expr.lastIndexOf('-');
                result = Double.parseDouble(expr.substring(0, idx)) - Double.parseDouble(expr.substring(idx + 1));
            } else if (expr.contains("*")) {
                String[] parts = expr.split("\\*", 2);
                result = Double.parseDouble(parts[0]) * Double.parseDouble(parts[1]);
            } else if (expr.contains("/")) {
                String[] parts = expr.split("/", 2);
                double divisor = Double.parseDouble(parts[1]);
                if (divisor == 0) return "错误：除数不能为零";
                result = Double.parseDouble(parts[0]) / divisor;
            } else {
                return "错误：不支持该运算符，请使用 +、-、*、/";
            }

            // 整数结果去掉小数点
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return String.format("%s = %d", expression, (long) result);
            }
            return String.format("%s = %.4f", expression, result);

        } catch (NumberFormatException e) {
            return "错误：表达式格式不正确，示例：'100 + 200'";
        }
    }

    /**
     * 模拟知识搜索（演示用途）
     */
    @Tool(name = "search_knowledge", description = "搜索相关知识或信息（演示用模拟搜索）")
    public String searchKnowledge(
            @ToolParam(name = "query", description = "搜索关键词或问题")
            String query) {
        String lowerQuery = query.toLowerCase();

        if (lowerQuery.contains("agentscope") || lowerQuery.contains("agent")) {
            return "搜索结果（AgentScope）：\n"
                    + "1. AgentScope 是一个面向 Agent 的编程框架\n"
                    + "2. 支持构建基于 LLM 的多 Agent 应用\n"
                    + "3. ReAct 范式：推理（Reasoning）→ 行动（Acting）循环\n"
                    + "4. 官网：https://java.agentscope.io";
        } else if (lowerQuery.contains("java") || lowerQuery.contains("spring")) {
            return "搜索结果（Java）：\n"
                    + "1. Java 是一种高级、面向对象的编程语言\n"
                    + "2. Spring Boot 简化了 Java 应用的配置和部署\n"
                    + "3. JDK 17 是目前的 LTS（长期支持）版本";
        } else if (lowerQuery.contains("react") || lowerQuery.contains("推理")) {
            return "搜索结果（ReAct 范式）：\n"
                    + "1. ReAct = Reasoning + Acting（推理 + 行动）\n"
                    + "2. Agent 先推理当前状态，再选择合适工具执行\n"
                    + "3. 执行结果作为新的观察结果，进入下一轮推理\n"
                    + "4. 循环直到任务完成或达到最大迭代次数";
        } else if (lowerQuery.contains("大模型") || lowerQuery.contains("llm") || lowerQuery.contains("qwen")) {
            return "搜索结果（大语言模型）：\n"
                    + "1. Qwen（通义千问）是阿里云推出的大语言模型系列\n"
                    + "2. 通过 DashScope API 可访问 qwen-max、qwen-plus 等模型\n"
                    + "3. 支持工具调用（Function Calling）能力";
        } else {
            return String.format(
                    "搜索结果（%s）：\n"
                            + "1. 关于 '%s' 的相关介绍（模拟）\n"
                            + "2. '%s' 的详细资料（模拟）\n"
                            + "3. 更多关于 '%s' 的参考链接（模拟）",
                    query, query, query, query);
        }
    }
}
