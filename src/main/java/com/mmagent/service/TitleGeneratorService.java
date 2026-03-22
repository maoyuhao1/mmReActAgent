package com.mmagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 会话标题自动生成服务
 *
 * <p>在第一轮对话结束后异步调用 DashScope，根据首句用户问题生成简短标题并写入 MongoDB。
 */
@Service
public class TitleGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(TitleGeneratorService.class);

    private static final String DASHSCOPE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private static final String TITLE_PROMPT =
            "请根据下面这句话，为对话起一个简洁的中文标题，要求：" +
            "不超过15个字、不加引号、不加标点、直接输出标题内容。\n用户说：%s";

    @Value("${agent.dashscope.api-key}")
    private String apiKey;

    @Value("${agent.dashscope.model-name:qwen-max}")
    private String modelName;

    private final WebClient webClient;
    private final ConversationPersistenceService persistenceService;

    public TitleGeneratorService(WebClient.Builder webClientBuilder,
                                  ConversationPersistenceService persistenceService) {
        this.webClient = webClientBuilder.build();
        this.persistenceService = persistenceService;
    }

    /**
     * 异步生成标题并写入会话，不阻塞调用方
     */
    public void generateAndSave(String sessionId, String userMessage) {
        buildRequest(userMessage)
                .flatMap(title -> persistenceService.updateSessionTitle(sessionId, title))
                .doOnSuccess(v -> log.info("[TITLE_GEN] sessionId={} 标题生成完成", sessionId))
                .doOnError(e -> log.warn("[TITLE_GEN] sessionId={} 标题生成失败: {}", sessionId, e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }

    private Mono<String> buildRequest(String userMessage) {
        String prompt = String.format(TITLE_PROMPT, userMessage);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 30,
                "temperature", 0.3
        );

        return webClient.post()
                .uri(DASHSCOPE_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractTitle);
    }

    @SuppressWarnings("unchecked")
    private String extractTitle(Map<?, ?> response) {
        try {
            List<?> choices = (List<?>) response.get("choices");
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            String title = (String) message.get("content");
            return title != null ? title.trim() : "新对话";
        } catch (Exception e) {
            log.warn("[TITLE_GEN] 解析标题响应失败", e);
            return "新对话";
        }
    }
}
