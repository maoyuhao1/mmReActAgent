package com.mmagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 互联网搜索工具
 *
 * <p>调用博查 AI 搜索 API（bochaai.com）获取搜索结果列表（标题 + 摘要 + URL）。
 * 国内可直接访问，注册后有免费额度。
 * 如需精读某个具体页面，配合 {@link UrlFetchTool} 使用。
 */
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String BOCHA_SEARCH_URL = "https://api.bochaai.com/v1/web-search";

    private final WebClient webClient;
    private final String apiKey;
    private final int maxResults;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSearchTool(WebClient webClient, String apiKey, int maxResults) {
        this.webClient = webClient;
        this.apiKey = apiKey;
        this.maxResults = maxResults;
    }

    @Tool(
        name = "web_search",
        description = "在互联网上搜索最新信息。" +
            "当问题涉及以下情况时必须调用：实时数据（股价/天气/汇率）、当前新闻事件、" +
            "近期发生的事情、你不确定的事实、需要引用来源的信息。" +
            "返回多条搜索结果，每条包含标题、摘要和来源URL。"
    )
    public Mono<String> webSearch(
            @ToolParam(name = "query", description = "搜索关键词，建议精炼准确，支持中英文")
            String query) {

        log.info("[WEB_SEARCH] query={}", query);

        ObjectNode body = objectMapper.createObjectNode()
                .put("query", query)
                .put("count", maxResults)
                .put("freshness", "noLimit")
                .put("summary", true);

        return webClient.post()
                .uri(BOCHA_SEARCH_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> formatResults(query, resp))
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(e -> {
                    log.error("[WEB_SEARCH] 搜索失败 query={}", query, e);
                    return Mono.just("搜索请求失败：" + e.getMessage() + "。请根据已有知识直接回答。");
                });
    }

    private String formatResults(String query, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查业务状态码
            int code = root.path("code").asInt(200);
            if (code != 200) {
                String msg = root.path("msg").asText(root.path("message").asText("未知错误"));
                log.error("[WEB_SEARCH] 博查 API 返回错误 code={} msg={}", code, msg);
                return "搜索服务返回错误（" + code + "）：" + msg;
            }

            // 实际结果在 data.webPages.value 下
            JsonNode values = root.path("data").path("webPages").path("value");

            if (values.isEmpty()) {
                return "未找到关于「" + query + "」的相关结果，请根据已有知识回答。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【搜索结果】关键词：").append(query).append("\n\n");

            int i = 1;
            for (JsonNode item : values) {
                String title = item.path("name").asText("（无标题）");
                String url = item.path("url").asText();
                String snippet = item.path("snippet").asText("（无摘要）");

                sb.append(i++).append(". ").append(title).append("\n");
                sb.append("   URL：").append(url).append("\n");
                sb.append("   摘要：").append(snippet).append("\n\n");
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[WEB_SEARCH] 解析响应失败", e);
            return "搜索结果解析失败：" + e.getMessage();
        }
    }
}
