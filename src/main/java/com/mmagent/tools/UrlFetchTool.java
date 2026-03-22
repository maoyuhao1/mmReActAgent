package com.mmagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 网页内容抓取工具
 *
 * <p>直接 HTTP GET 目标 URL，用 Jsoup 提取正文文本，无需境外代理服务。
 * 适用于 {@link WebSearchTool} 搜索后需要精读某个具体页面的场景。
 * 内容超过 {@value #MAX_CONTENT_LENGTH} 字符时自动截断，避免 Token 溢出。
 */
public class UrlFetchTool {

    private static final Logger log = LoggerFactory.getLogger(UrlFetchTool.class);
    private static final int MAX_CONTENT_LENGTH = 5000;

    private final WebClient webClient;

    public UrlFetchTool(WebClient webClient) {
        this.webClient = webClient;
    }

    @Tool(
        name = "fetch_url",
        description = "读取指定网页的完整文本内容。" +
            "当 web_search 返回的摘要信息不足以回答问题，需要阅读某个具体网页的完整内容时使用。" +
            "URL 必须以 http:// 或 https:// 开头。"
    )
    public Mono<String> fetchUrl(
            @ToolParam(name = "url", description = "要读取的网页完整URL，必须以 http:// 或 https:// 开头")
            String url) {

        if (url == null || url.isBlank()) {
            return Mono.just("错误：URL 不能为空。");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Mono.just("错误：URL 必须以 http:// 或 https:// 开头，收到：" + url);
        }

        log.info("[FETCH_URL] url={}", url);

        return webClient.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml")
                .retrieve()
                .bodyToMono(String.class)
                .map(html -> extractText(url, html))
                .timeout(Duration.ofSeconds(20))
                .onErrorResume(e -> {
                    log.error("[FETCH_URL] 获取网页失败 url={}", url, e);
                    return Mono.just("获取网页内容失败：" + e.getMessage());
                });
    }

    /**
     * 用 Jsoup 解析 HTML，提取标题和正文，过滤脚本/样式等噪音
     */
    private String extractText(String url, String html) {
        try {
            Document doc = Jsoup.parse(html);

            // 移除脚本、样式、导航、广告等噪音元素
            doc.select("script, style, nav, footer, header, aside, iframe, noscript").remove();

            String title = doc.title();
            String body = doc.body() != null ? doc.body().text() : "";

            if (body.isBlank()) {
                return "网页内容为空：" + url;
            }

            String header = "【网页内容】" + (title.isBlank() ? "" : title + "\n") + "来源：" + url + "\n\n";
            String content = header + body;

            if (content.length() > MAX_CONTENT_LENGTH) {
                return content.substring(0, MAX_CONTENT_LENGTH)
                        + "\n\n... [内容已截断，仅展示前 " + MAX_CONTENT_LENGTH + " 字符]";
            }
            return content;
        } catch (Exception e) {
            log.error("[FETCH_URL] HTML 解析失败 url={}", url, e);
            return "网页解析失败：" + e.getMessage();
        }
    }
}
