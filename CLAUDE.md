# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 语言

所有回答及代码注释请使用中文。

## 构建与运行

```bash
# 运行应用（需先设置环境变量）
export DASHSCOPE_API_KEY="your-actual-api-key"
export BOCHA_API_KEY="your-bocha-api-key"   # 可选，互联网搜索工具用
unset http_proxy && unset https_proxy && unset HTTP_PROXY && unset HTTPS_PROXY && mvn spring-boot:run

# 编译
unset http_proxy && unset https_proxy && unset HTTP_PROXY && unset HTTPS_PROXY && mvn compile

# 打包
unset http_proxy && unset https_proxy && unset HTTP_PROXY && unset HTTPS_PROXY && mvn package -DskipTests

# 运行 JAR
java -jar target/my-agentscope-1.0-SNAPSHOT.jar
```

**必须环境变量**：
- `DASHSCOPE_API_KEY`：阿里云 DashScope API Key，用于调用 Qwen 模型（必须）
- `BOCHA_API_KEY`：博查搜索 API Key，用于 `web_search` 工具（不设则搜索功能不可用）

## 中间件部署（Docker）

Redis 和 MongoDB 通过 `docker-compose.yml` 启动，数据持久化在本地 `data/` 目录。

```bash
# 启动所有中间件
docker compose up -d

# 停止
docker compose down
```

### Redis

| 配置项 | 值 |
|-------|-----|
| 镜像 | `redis:7.2-alpine` |
| 容器名 | `mmagent-redis` |
| 端口 | `6379:6379` |
| 密码 | `admin123`（application.yml 中配置） |
| 最大内存 | `256mb` |
| 淘汰策略 | `allkeys-lru`（内存满时自动淘汰最久未用的 key） |
| 数据卷 | `./data/redis:/data` |

**Redis 中存储的内容**：

| Key 模式 | 用途 | TTL |
|---------|------|-----|
| `session:lock:{sessionId}` | 会话并发锁（Redisson 分布式锁） | 300 秒（自动释放） |

> Memory 已迁移至 MongoDB `agentscope_sessions` 集合存储，Redis 仅保留分布式锁用途。

### MongoDB

| 配置项 | 值 |
|-------|-----|
| 镜像 | `mongo:7.0` |
| 容器名 | `mmagent-mongo` |
| 端口 | `27017:27017` |
| Root 用户 | `root / root123`（仅初始化用） |
| 应用用户 | `mmagent / mmagent123`（`mmagent_db` 库 readWrite 权限） |
| 数据库名 | `mmagent_db` |
| 数据卷 | `./data/mongodb:/data/db` |
| 初始化脚本 | `./mongo-init/init-user.js`（创建用户、集合、索引） |

**MongoDB 集合及索引**：

| 集合 | 存储内容 | TTL |
|-----|---------|-----|
| `sessions` | 会话元数据（状态、标题、收藏、最后活跃时间） | 永久 |
| `conversations` | 每轮对话记录（用户输入、AI 回复、工具调用、耗时） | 永久 |
| `stream_events` | 工具调用/结果/错误流式事件（调试用） | **7 天自动删除**（TTL 索引） |
| `agentscope_sessions` | AgentScope AutoContextMemory 消息按行存储（含压缩历史），结构参照 MySQL `agentscope_sessions` 表 | 永久（随会话删除） |

`agentscope_sessions` 文档结构：`_id={sessionId}:{stateKey}:{itemIndex}`，`stateData` 为 JSON 序列化的单条消息，`stateKey` 为 `working_messages` 或 `working_messages:_hash` 等。

MongoDB 索引在 `MongoSchemaConfig.java` 中通过代码维护，应用启动时自动创建（幂等）。

## 架构概览

基于 **Spring Boot 3.2.5 + AgentScope 1.0.10** 的 ReAct Agent Web 应用，使用阿里云 DashScope Qwen 模型，通过 SSE 实现流式对话输出。

### 核心数据流

```
客户端 POST /api/chat/stream
  → ChatController（WebFlux SSE）
  → ReactAgentService（会话锁 + 内存加载 + Flux 流）
  → ReActAgent（AgentScope Hook 机制）
  → DashScope API（qwen-max 模型）
  → 工具调用（GetCurrentTimeTool / CalculateTool / WebSearchTool / UrlFetchTool）
  → saveMemoryToSession → MongoDB（agentscope_sessions，永久存储）
  → persistenceService → MongoDB（conversations / stream_events）
  → 流式 SSE 事件推送回客户端
```

### 关键组件

| 组件 | 路径 | 职责 |
|-----|------|------|
| `AgentConfig` | `config/AgentConfig.java` | 定义 Agent、Model、Toolkit、MongoSession Bean |
| `ReactAgentService` | `service/ReactAgentService.java` | 多会话管理核心：MongoDB 内存恢复、Hook 捕获、流式推送、持久化 |
| `MongoSession` | `session/MongoSession.java` | AgentScope Session 接口实现，Memory 按行存入 agentscope_sessions |
| `ConversationPersistenceService` | `service/ConversationPersistenceService.java` | 异步写 MongoDB（会话/对话/流事件） |
| `RateLimitService` | `service/RateLimitService.java` | Redisson 分布式会话锁（防并发覆盖） |
| `TitleGeneratorService` | `service/TitleGeneratorService.java` | 首轮对话结束后异步生成会话标题 |
| `MongoSchemaConfig` | `config/MongoSchemaConfig.java` | 应用启动时创建 MongoDB TTL 索引和复合索引 |
| `ChatController` | `controller/ChatController.java` | `POST /api/chat/stream`，返回 SSE 流 |
| `SessionController` | `controller/SessionController.java` | 会话 CRUD、标题更新、收藏切换 |
| `AdminController` | `controller/AdminController.java` | 运维接口：统计、工具列表、强制删会话 |

### Agent 工具

| 工具名 | 类文件 | 功能 |
|-------|-------|------|
| `get_current_time` | `tools/GetCurrentTimeTool.java` | 查询指定时区当前时间 |
| `calculate` | `tools/CalculateTool.java` | 计算数学表达式（加减乘除） |
| `web_search` | `tools/WebSearchTool.java` | 调用博查 API 进行互联网搜索 |
| `fetch_url` | `tools/UrlFetchTool.java` | 用 Jsoup 抓取网页内容（5000 字符截断） |

新增工具：在 `tools/` 下新建类，方法加 AgentScope `@Tool` 注解，并在 `AgentConfig.java` 中注册到 `Toolkit`。

### SSE 事件类型

| type | 含义 |
|------|------|
| `chunk` | LLM 推理流式 token |
| `tool_call` | Agent 决定调用工具 |
| `tool_result` | 工具执行结果 |
| `done` | 对话完成 |
| `error` | 发生错误 |
| `heartbeat` | 心跳（每 15 秒），防止网关断连 |

### 主要 API 接口

| 方法 | 路径 | 功能 |
|-----|------|------|
| `POST` | `/api/chat/stream` | 流式对话（SSE） |
| `POST` | `/api/sessions` | 创建新会话 |
| `DELETE` | `/api/sessions/{sessionId}` | 销毁会话 |
| `GET` | `/api/sessions` | 查询所有会话 |
| `GET` | `/api/sessions/{sessionId}/conversations` | 查询会话历史 |
| `PATCH` | `/api/sessions/{sessionId}/title` | 更新标题 |
| `PATCH` | `/api/sessions/{sessionId}/favorite` | 切换收藏 |
| `GET` | `/api/admin/stats` | 运维统计 |
| `GET` | `/api/admin/tools` | 已注册工具列表 |
| `GET` | `/api/admin/sessions` | 所有会话（含 running） |
| `DELETE` | `/api/admin/sessions/{sessionId}` | 强制销毁会话 |

### 关键配置项（application.yml）

```yaml
agent:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY}
    model-name: qwen-max          # 可换为 qwen-plus 等
  react:
    max-iters: 10                 # ReAct 最大循环次数
  memory:
    msg-threshold: 60             # 消息数超过此值触发上下文压缩
    max-token: 28000              # qwen-max 可用上下文上限
    token-ratio: 0.75             # Token 使用率超 75% 触发压缩
    last-keep: 20                 # 最近 N 条消息不参与压缩
search:
  bocha:
    api-key: ${BOCHA_API_KEY}
    max-results: 5
```

### 熔断保护（Resilience4j）

Circuit Breaker 名称：`dashscope-agent`，保护 DashScope API 调用。

- 滑动窗口：10 次请求，失败率 > 50% 打开熔断
- 半开恢复等待：30 秒，允许 3 次探测请求
- 慢调用阈值：30 秒，慢调用率 > 80% 也触发熔断

### 日志

日志配置在 `src/main/resources/logback-spring.xml`，输出到项目根目录 `logs/` 下。

| 文件 | 内容 | 滚动策略 | 保留 |
|-----|------|---------|------|
| `logs/app.log` | 全量业务日志（INFO 及以上） | 按天 + 100MB 分片 | 30 天 / 总量 3GB |
| `logs/model-request.log` | 模型请求完整入参 JSON，每行含 `[sid:xx] [cid:xx]` 前缀（MDC） | 按天 + 50MB 分片 | 7 天 / 总量 500MB |
| `logs/error.log` | ERROR 及以上日志（独立文件，便于告警） | 按天 + 50MB 分片 | 30 天 |

**日志级别**：
- `com.mmagent`：INFO（业务日志）
- `model-request`：DEBUG（模型请求，additivity=false，仅写独立文件）
- `io.agentscope`、`org.springframework`、`reactor`：INFO

**查看日志**：
```bash
# 实时跟踪业务日志
tail -f logs/app.log

# 查看模型请求入参（调试推理过程）
tail -f logs/model-request.log

# 查看错误
tail -f logs/error.log
```
