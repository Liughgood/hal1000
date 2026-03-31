package com.genhao.hal1000.llm;

import org.springframework.web.reactive.function.client.WebClientResponseException;

public final class LlmErrorUtil {
    private LlmErrorUtil() {
    }

    public static String toFriendlyError(Throwable err) {
        if (err instanceof WebClientResponseException wcre) {
            var code = wcre.getStatusCode().value();
            var upstream = safeOneLine(wcre.getResponseBodyAsString());
            return switch (code) {
                case 401, 403 -> "LLM 鉴权失败（" + code + "）。请检查 API Key 是否正确、是否有权限调用该模型。"
                        + (upstream.isBlank() ? "" : " 上游返回：" + upstream);
                case 429 -> "LLM 请求过于频繁（429）。可能是额度/并发/速率限制触发，请稍后再试或更换 key/提升额度。"
                        + (upstream.isBlank() ? "" : " 上游返回：" + upstream);
                default -> "LLM 请求失败（" + code + "）。"
                        + (upstream.isBlank() ? "" : " 上游返回：" + upstream);
            };
        }
        if (err instanceof IllegalArgumentException iae) {
            return iae.getMessage() == null ? "参数错误" : iae.getMessage();
        }
        return err.getMessage() == null ? "LLM 请求失败（未知错误）" : err.getMessage();
    }

    private static String safeOneLine(String s) {
        if (s == null) return "";
        var t = s.strip();
        if (t.length() > 500) t = t.substring(0, 500) + "...";
        return t.replaceAll("\\s+", " ");
    }
}

