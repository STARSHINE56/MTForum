package com.solosu.mtforum.network;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import okhttp3.HttpUrl;

/** Page-level checks, deliberately separate from login and action responses. */
public final class ForumPageGuard {
    public static final String BASE_URL = "https://bbs.binmt.cc/";
    private ForumPageGuard() {}

    public static Document parse(String html) { return parse(html, "page_guard"); }

    public static final class PageFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final String stage;
        public final String reason;
        private PageFailure(String stage, String reason, String message) {
            super(message + "（" + stage + "/" + reason + "）");
            this.stage = stage;
            this.reason = reason;
        }
    }
    static PageFailure failure(String stage, String reason, String message) {
        ForumDiagnostics.failure(stage, reason);
        return new PageFailure(stage, reason, message);
    }

    public static Document parse(String html, String stage) {
        ForumDiagnostics.html(stage, html);
        if (html == null || html.trim().isEmpty()) throw failure(stage, "empty_response", "论坛返回空页面，请重试");
        Document doc = Jsoup.parse(html, BASE_URL);
        String title = doc.title().toLowerCase(java.util.Locale.ROOT);
        boolean hasContent = !doc.select("li.forumlist_li, .comiis_postli, .comiis_message, td.t_f, #threadlist").isEmpty();
        if (!hasContent && (!doc.select("form[action*=logging] input[type=password], form[action*=login] input[type=password]").isEmpty()
                || title.contains("登录") || title.contains("log in"))) {
            throw failure(stage, "login", "需要登录或登录已失效，请重新登录");
        }
        if (VerificationPolicy.challenge(html, "text/html")) {
            throw failure(stage, "verification", "论坛访问验证未完成，请稍后重试");
        }
        Element alert = doc.select("#messagetext, .comiis_tip, .alert_error").first();
        if (!hasContent && alert != null && !alert.text().trim().isEmpty()) {
            throw failure(stage, "forum_alert", "论坛提示：" + alert.text());
        }
        if (!hasContent && (title.matches(".*\\b[45][0-9]{2}\\b.*")
                || title.contains("access denied") || title.contains("bad gateway") || title.contains("service unavailable"))) {
            throw failure(stage, "error_html", "论坛返回错误页面，请稍后重试");
        }
        return doc;
    }

    public static boolean explicitEmpty(Document doc) {
        // Only inspect empty-state containers, never quoted post content or the entire page.
        for (Element e : doc.select(".comiis_notip, .comiis_none, #threadlist .emp, .comiis_forumlist .emp")) {
            String text = e.text();
            if (text.contains("暂无") || text.contains("没有") || text.contains("无相关")) return true;
        }
        return false;
    }

    public static void requireList(Document doc, int count) { requireList(doc, count, "thread_list"); }
    public static void requireList(Document doc, int count, String stage) {
        if (count == 0 && !explicitEmpty(doc)) {
            throw failure(stage, "selector_mismatch", "帖子列表解析失败，页面格式可能已变化，请重试");
        }
        ForumDiagnostics.parsed(stage, count);
    }

    public static String imageUrl(String source, String pageUrl) {
        if (source == null || source.trim().isEmpty()) return null;
        String value = source.trim();
        if (value.matches("(?i)^(javascript|data|file|content):.*")) return null;
        HttpUrl base = HttpUrl.parse(pageUrl);
        HttpUrl resolved = base == null ? null : base.resolve(value);
        if (resolved == null) return null;
        if (resolved.host().equals("bbs.binmt.cc") || resolved.host().equals("cdn.binmt.cc")) {
            resolved = resolved.newBuilder().scheme("https").build();
        }
        if (!resolved.username().isEmpty() || !resolved.password().isEmpty()) return null;
        return resolved.newBuilder().fragment(null).build().toString();
    }
}
