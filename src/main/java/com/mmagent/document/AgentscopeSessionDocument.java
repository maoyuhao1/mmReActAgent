package com.mmagent.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * AgentScope Session 消息存储文档 - 对应 MongoDB agentscope_sessions 集合
 *
 * <p>结构参照 AgentScope MySQL 实现（agentscope_sessions 表），每条消息单独一行：
 * <ul>
 *   <li>单值状态（如 hash）：itemIndex = 0
 *   <li>列表状态（如消息历史）：itemIndex = 0, 1, 2, ...，每条消息一条文档
 * </ul>
 *
 * <p>_id 使用 "{sessionId}:{stateKey}:{itemIndex}" 组合键，对应 MySQL 联合主键，
 * 支持 save 时直接 upsert，无需额外判断。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "agentscope_sessions")
public class AgentscopeSessionDocument {

    /** 联合主键：{sessionId}:{stateKey}:{itemIndex} */
    @Id
    private String id;

    /** 会话 ID */
    private String sessionId;

    /** 状态键（如 "working_messages"、"working_messages:_hash"） */
    private String stateKey;

    /** 列表索引；单值状态固定为 0 */
    private int itemIndex;

    /** JSON 序列化后的状态数据 */
    private String stateData;

    private Instant createdAt;
    private Instant updatedAt;
}
