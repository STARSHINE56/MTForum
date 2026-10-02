package com.solosu.mtforum.network;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import okhttp3.HttpUrl;

/** Recognizes interstitials without treating login, XML fragments or broken templates as challenges. */
public final class VerificationPolicy {
    private VerificationPolicy() {}

    public static boolean trustedUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.isHttps() && parsed.host().equals("bbs.binmt.cc")
                && parsed.port() == 443 && parsed.username().isEmpty() && parsed.password().isEmpty();
    }

    public static boolean forumContent(String html) {
        if (html == null || html.trim().isEmpty()) return false;
        Document doc = Jsoup.parse(html);
        return !doc.select("li.forumlist_li, .comiis_postli, .comiis_message, td.t_f, #threadlist, "
                + ".comiis_forumlist, .comiis_notip, .comiis_none, #ct .fl, #postlist, "
                + "a[href*=forumdisplay], a[href*=viewthread], a[href^=thread-], a[href^=forum-]").isEmpty();
    }

    public static boolean challenge(String html, String contentType) {
        if (html == null || html.trim().isEmpty()) return false;
        String head = html.trim();
        if (head.startsWith("<?xml") || head.startsWith("<root") || head.contains("<![CDATA[")) return false;
        if (contentType != null && !contentType.isEmpty()
                && !contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) return false;
        if (forumContent(html)) return false;
        Document doc = Jsoup.parse(html);
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        // Reference project also recognizes vendor-neutral short script stubs (e.g. ESA),
        // which contain no "captcha" words and sometimes no explicit body element.
        boolean skeleton = lower.contains("discuz") || lower.contains("formhash") || lower.contains("comiis")
                || !doc.select("#ct, #hd, #ft, #postlist, #thread_subject").isEmpty();
        if (skeleton) return false;
        if (!doc.select("form[action*=logging] input[type=password], form[action*=login] input[type=password]").isEmpty()) return false;
        String title = doc.title().toLowerCase(java.util.Locale.ROOT);
        if (title.contains("登录") || title.contains("log in")) return false;
        if (!doc.select("#challenge-form, #cf-challenge-running, .g-recaptcha, .h-captcha, "
                + ".cf-turnstile, script[src*='cdn-cgi/challenge'], script[src*='challenges.cloudflare.com']").isEmpty()) return true;
        if (title.contains("just a moment") || title.contains("verify") || title.contains("verification")
                || title.contains("人机验证") || title.contains("安全验证") || title.contains("浏览器检查")
                || title.contains("checking your browser")) return true;
        boolean shortScriptStub = html.length() <= 65536 && lower.contains("<html")
                && (!doc.select("script, meta[http-equiv=refresh]").isEmpty())
                && (!lower.contains("<body") || doc.body().text().trim().length() < 256)
                && !title.matches(".*\\b[45][0-9]{2}\\b.*")
                && !title.contains("access denied") && !title.contains("bad gateway")
                && !title.contains("service unavailable");
        return shortScriptStub || lower.contains("_cf_chl_opt") || lower.contains("/cdn-cgi/challenge-platform/")
                || lower.contains("checking your browser") || lower.contains("verify you are human")
                || lower.contains("正在进行人机验证") || lower.contains("正在验证您的浏览器");
    }
}
