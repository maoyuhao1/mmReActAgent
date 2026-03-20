# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 语言

所有回答及代码注释请使用中文。

## 构建与运行

```bash
# 运行应用（需先设置环境变量）
export DASHSCOPE_API_KEY="your-actual-api-key"
unset http_proxy && unset https_proxy && unset HTTP_PROXY && unset HTTPS_PROXY && mvn spring-boot:run

# 编译
unset http_proxy && unset https_proxy && unset HTTP_PROXY && unset HTTPS_PROXY && mvn compile

# 打包
unset http_proxy && unset https_proxy && unset HTTP_PROXY && unset HTTPS_PROXY && mvn package -DskipTests

# 运行 JAR
java -jar target/my-agentscope-1.0-SNAPSHOT.jar
```

**必须环境变量**：`DASHSCOPE_API_KEY`（DashScope API Key，用于调用 Qwen 模型）

## 架构概览

这是一个基于 **Spring Boot 3.2.5 + AgentScope 1.0.10** 的 ReAct Agent Web 应用，使用阿里云 DashScope 的 Qwen 模型，通过 SSE（Server-Sent Events）实现流式对话输出。

### 核心数据流

```
客户端 POST /api/chat/stream
  → ChatController（WebFlux SSE）
  → ReactAgentService（会话管理 + Flux 流）
  → ReActAgent（AgentScope Hook机制）
  → DashScope API（qwen-max 模型）
  → 工具调用（MyTools）
  → 流式 SSE 事件推送回客户端
```

### 关键组件

- **`config/AgentConfig.java`**：定义 `ReActAgent` 和 `Toolkit` 两个 Spring Bean，从 `application.yml` 读取配置（api-key、model-name、max-iters、sys-prompt）
- **`service/ReactAgentService.java`**：多会话管理核心，用 `ConcurrentHashMap<String, ReActAgent>` 维护会话，通过 AgentScope 的 Hook 机制（`ReasoningChunkEvent`、`PreActingEvent`、`PostActingEvent`）捕获流式事件，转化为 Reactor `Flux<String>` 推送
- **`controller/ChatController.java`**：暴露四个接口，核心是 `POST /api/chat/stream`（返回 SSE 流），CORS 允许所有来源
- **`tools/MyTools.java`**：Agent 可调用的工具，目前有 `get_current_time`、`calculate`、`search_knowledge`

### SSE 事件类型

| type | 含义 |
|------|------|
| `chunk` | LLM 推理流式 token |
| `tool_call` | Agent 决定调用工具 |
| `tool_result` | 工具执行结果 |
| `done` | 对话完成 |
| `error` | 发生错误 |

### 配置项（application.yml）

```yaml
agent:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:your-api-key-here}
    model-name: qwen-max      # 可换为 qwen-plus 等
  react:
    max-iters: 10             # ReAct 最大循环次数
    sys-prompt: >             # 系统提示词
```

## 添加新工具

在 `MyTools.java` 中新增方法，用 AgentScope 的 `@Tool` 注解描述工具功能和参数，AgentScope 会自动注册到 `Toolkit`，Agent 即可调用。
