package com.mmagent.config;

import com.mmagent.document.AgentscopeSessionDocument;
import com.mmagent.document.ConversationDocument;
import com.mmagent.document.StreamEventDocument;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;

import java.util.concurrent.TimeUnit;

/**
 * MongoDB 索引初始化配置
 *
 * <p>应用启动时确保以下索引存在：
 * 1. stream_events.timestamp — TTL 7 天，防止数据无限膨胀
 * 2. conversations.(sessionId, turnIndex) — 复合索引，加速历史对话加载查询
 * 3. agentscope_sessions.(sessionId, stateKey, itemIndex) — 唯一复合索引，对应 MySQL 联合主键
 */
@Configuration
public class MongoSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaConfig.class);

    private final ReactiveMongoTemplate mongoTemplate;

    public MongoSchemaConfig(ReactiveMongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void ensureIndexes() {
        // stream_events TTL 索引：7 天后自动删除
        mongoTemplate.indexOps(StreamEventDocument.class)
                .ensureIndex(new Index().on("timestamp", Sort.Direction.ASC)
                        .expire(7, TimeUnit.DAYS)
                        .named("ts_ttl_7d"))
                .subscribe(
                        name -> log.info("[INDEX] stream_events TTL 索引已确保: {}", name),
                        e -> log.error("[INDEX] TTL 索引创建失败", e)
                );

        // conversations 复合索引：支撑历史加载查询 findBySessionIdOrderByTurnIndexAsc
        mongoTemplate.indexOps(ConversationDocument.class)
                .ensureIndex(new CompoundIndexDefinition(
                        new Document("sessionId", 1).append("turnIndex", 1)))
                .subscribe(
                        name -> log.info("[INDEX] conversations 复合索引已确保: {}", name),
                        e -> log.error("[INDEX] 复合索引创建失败", e)
                );

        // agentscope_sessions 唯一复合索引：对应 MySQL 联合主键 (session_id, state_key, item_index)
        // 加速 MongoSession 的按 sessionId+stateKey 查询及 itemIndex 排序
        mongoTemplate.indexOps(AgentscopeSessionDocument.class)
                .ensureIndex(new CompoundIndexDefinition(
                        new Document("sessionId", 1).append("stateKey", 1).append("itemIndex", 1))
                        .named("session_state_item_idx"))
                .subscribe(
                        name -> log.info("[INDEX] agentscope_sessions 复合索引已确保: {}", name),
                        e -> log.error("[INDEX] agentscope_sessions 索引创建失败", e)
                );
    }
}
