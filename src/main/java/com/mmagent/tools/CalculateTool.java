package com.mmagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 数学计算工具：计算加减乘除表达式
 */
public class CalculateTool {

    @Tool(name = "calculate", description = "计算数学表达式，支持加减乘除运算")
    public String calculate(
            @ToolParam(name = "expression", description = "数学表达式，例如 '100 + 200'、'36 * 48'、'1000 / 4'")
            String expression) {
        try {
            String expr = expression.replaceAll("\\s+", "");
            double result;

            if (expr.contains("+")) {
                String[] parts = expr.split("\\+", 2);
                result = Double.parseDouble(parts[0]) + Double.parseDouble(parts[1]);
            } else if (expr.matches(".*[^e]-.*")) {
                // 避免科学计数法中的负号误匹配
                int idx = expr.lastIndexOf('-');
                result = Double.parseDouble(expr.substring(0, idx)) - Double.parseDouble(expr.substring(idx + 1));
            } else if (expr.contains("*")) {
                String[] parts = expr.split("\\*", 2);
                result = Double.parseDouble(parts[0]) * Double.parseDouble(parts[1]);
            } else if (expr.contains("/")) {
                String[] parts = expr.split("/", 2);
                double divisor = Double.parseDouble(parts[1]);
                if (divisor == 0) return "错误：除数不能为零";
                result = Double.parseDouble(parts[0]) / divisor;
            } else {
                return "错误：不支持该运算符，请使用 +、-、*、/";
            }

            // 整数结果去掉小数点
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return String.format("%s = %d", expression, (long) result);
            }
            return String.format("%s = %.4f", expression, result);

        } catch (NumberFormatException e) {
            return "错误：表达式格式不正确，示例：'100 + 200'";
        }
    }
}
