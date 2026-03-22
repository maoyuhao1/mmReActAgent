package com.mmagent.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 会话文档 - 对应 MongoDB sessions 集合
 */
@Data
@Builder
@Document(collection = "sessions")
public class SessionDocument {

    @Id
    private String id; // 即 sessionId

    /** 会话创建时间 */
    private Instant createdAt;

    /** 最后活跃时间 */
    private Instant lastActiveAt;

    /** 状态：active / closed */
    private String status;

    /** 累计消息轮次 */
    private int messageCount;

    /** 会话标题（用户自定义，默认为空） */
    private String title;

    /** 是否收藏（旧文档无此字段时为 null，等同于 false） */
    private Boolean favorite;
}
