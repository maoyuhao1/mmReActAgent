package com.mmagent.model;

import lombok.Data;

@Data
public class ChatRequest {

    /** 用户输入消息 */
    private String message;

    /** 会话 ID（为空则创建新会话） */
    private String sessionId;
}
