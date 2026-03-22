package com.mmagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间查询工具：获取指定时区的当前时间
 */
public class GetCurrentTimeTool {

    @Tool(name = "get_current_time", description = "获取指定时区的当前时间")
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "时区名称，例如 'Asia/Shanghai'、'America/New_York'、'Europe/London'")
            String timezone) {
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            LocalDateTime now = LocalDateTime.now(zoneId);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return String.format("当前时间（%s）：%s", timezone, now.format(formatter));
        } catch (Exception e) {
            return "错误：无效的时区名称，请使用如 'Asia/Shanghai' 的格式";
        }
    }
}
