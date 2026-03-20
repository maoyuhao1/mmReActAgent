package com.mmagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 事件数据结构
 * type: "chunk" | "tool_call" | "tool_result" | "done" | "error"
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseEvent {

    /** 事件类型 */
    private String type;

    /** 事件内容 */
    private String content;

    /** 会话 ID */
    private String sessionId;

    public static SseEvent chunk(String content, String sessionId) {
        return new SseEvent("chunk", content, sessionId);
    }

    public static SseEvent toolCall(String content, String sessionId) {
        return new SseEvent("tool_call", content, sessionId);
    }

    public static SseEvent toolResult(String content, String sessionId) {
        return new SseEvent("tool_result", content, sessionId);
    }

    public static SseEvent done(String sessionId) {
        return new SseEvent("done", "[DONE]", sessionId);
    }

    public static SseEvent error(String message, String sessionId) {
        return new SseEvent("error", message, sessionId);
    }

    public static SseEvent heartbeat(String sessionId) {
        return new SseEvent("heartbeat", "", sessionId);
    }
}
