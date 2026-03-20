package com.mmagent.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * 对话文档 - 对应 MongoDB conversations 集合
 * 每轮用户问答对应一条记录
 */
@Data
@Builder
@Document(collection = "conversations")
public class ConversationDocument {

    @Id
    private String id;

    /** 所属会话 ID */
    private String sessionId;

    /** 本次对话在会话中的轮次索引（从 0 开始） */
    private int turnIndex;

    /** 用户输入 */
    private String userMessage;

    /** AI 最终完整回复（对话结束后一次写入） */
    private String aiReply;

    /** 工具调用记录列表 */
    private List<ToolCallRecord> toolCalls;

    /** 对话开始时间 */
    private Instant startTime;

    /** 对话结束时间 */
    private Instant endTime;

    /** 耗时（毫秒） */
    private long durationMs;

    /** 状态：running / done / error */
    private String status;

    /** 错误信息（status=error 时填充） */
    private String errorMsg;

    /**
     * 工具调用内嵌记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallRecord {
        /** 工具名称 */
        private String toolName;
        /** 输入参数（JSON 字符串） */
        private String inputArgs;
        /** 工具返回结果 */
        private String result;
        /** 工具执行耗时（毫秒） */
        private long durationMs;
    }
}
