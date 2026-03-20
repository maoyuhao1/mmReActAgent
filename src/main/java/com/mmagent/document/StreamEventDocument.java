package com.mmagent.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 流式事件文档 - 对应 MongoDB stream_events 集合
 * 用于实时记录工具调用、工具结果、错误等关键事件
 */
@Data
@Builder
@Document(collection = "stream_events")
public class StreamEventDocument {

    @Id
    private String id;

    /** 所属会话 ID */
    private String sessionId;

    /** 所属对话 ID */
    private String conversationId;

    /** 事件类型：tool_call / tool_result / error */
    private String eventType;

    /** 事件内容 */
    private String content;

    /** 事件发生时间 */
    private Instant timestamp;

    /** 事件顺序号（同一 conversation 内单调递增） */
    private int sequence;
}
