package com.solosu.mtforum.network;

import java.io.IOException;

/** Reject transport failures before HTML or image consumers see their bodies. */
public final class ResponsePolicy {
    private ResponsePolicy() {}

    public static void checkStatus(int code) throws IOException {
        if (code >= 200 && code < 300) return;
        String reason;
        if (code == 401) reason = "登录已失效，请重新登录";
        else if (code == 403) reason = "论坛拒绝访问，请在浏览器检查登录或验证要求";
        else if (code == 404) reason = "请求的页面或图片不存在";
        else if (code == 429) reason = "请求过于频繁，请稍后重试";
        else if (code >= 500) reason = "论坛服务暂时不可用，请稍后重试";
        else if (code >= 300 && code < 400) reason = "页面重定向未完成";
        else reason = "网络请求失败";
        throw new IOException(reason + "（HTTP " + code + "）");
    }

    public static String errorMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.io.InterruptedIOException) return "网络请求超时，请重试";
            if (cause instanceof java.net.UnknownHostException) return "无法解析论坛地址，请检查网络";
            if (cause instanceof java.net.ConnectException) return "无法连接论坛，请检查网络或稍后重试";
        }
        return error.getMessage() == null ? "网络请求失败，请重试" : error.getMessage();
    }
}
