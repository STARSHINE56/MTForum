package com.solosu.mtforum.network;

import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Logs only allowlisted metadata and selector counts, never HTML, headers or exception messages. */
public final class ForumDiagnostics {
    public interface Sink { void write(String line); }
    private static volatile Sink sink = line -> {};
    private static final AtomicLong sequence = new AtomicLong();
    private static final ThreadLocal<Long> requestId = new ThreadLocal<>();
    private ForumDiagnostics() {}

    public static void setSink(Sink value) { sink = value == null ? line -> {} : value; }
    private static void emit(String event) {
        try { sink.write("request=" + (requestId.get() == null ? 0 : requestId.get()) + " " + event); }
        catch (RuntimeException ignored) { /* Diagnostics must not break page loading. */ }
    }
    public static void start(String url) {
        requestId.set(sequence.incrementAndGet());
        emit("stage=request url=" + safeUrl(url));
    }
    public static void response(Response response) {
        int redirects = 0;
        for (Response prior = response.priorResponse(); prior != null; prior = prior.priorResponse()) redirects++;
        emit("stage=response status=" + response.code() + " final=" + safeUrl(response.request().url().toString())
                + " redirects=" + redirects + " contentType=" + safeContentType(response.header("Content-Type")));
    }
    public static void failure(String stage, String reason) { emit("stage=" + stage + " reason=" + reason); }
    public static void transportFailure(Throwable error) { failure("transport", transportReason(error)); }
    public static String transportReason(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.UnknownHostException) return "dns";
            if (cause instanceof javax.net.ssl.SSLException) return "tls";
            if (cause instanceof java.io.InterruptedIOException) return "timeout_or_cancelled";
            if (cause instanceof java.net.ConnectException) return "connect";
        }
        return "io";
    }
    public static void html(String stage, String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        imageHtml(stage + "_images", html, "https://bbs.binmt.cc/");
        String title = doc.title().toLowerCase(java.util.Locale.ROOT);
        emit("stage=" + stage + " chars=" + (html == null ? 0 : html.length())
                + " li=" + doc.select("li.forumlist_li").size()
                + " threadLinks=" + doc.select("a[href*=thread-],a[href*=viewthread][href*=tid=]").size()
                + " desktopRows=" + doc.select("tbody[id^=normalthread_]").size()
                + " guideRows=" + doc.select("#threadlist tr").size()
                + " posts=" + doc.select(".comiis_postli").size()
                + " body=" + doc.select(".comiis_message,td.t_f,div.postbody,article").size()
                + " forums=" + doc.select("a[href*=forum-],a[href*=forumdisplay][href*=fid=]").size()
                + " loginForms=" + doc.select("form[action*=logging] input[type=password],form[action*=login] input[type=password]").size()
                + " challenges=" + doc.select("#challenge-form,#cf-challenge-running,.g-recaptcha,.h-captcha").size()
                + " alerts=" + doc.select("#messagetext,.comiis_tip,.alert_error").size()
                + " guardContent=" + doc.select("li.forumlist_li,.comiis_postli,.comiis_message,td.t_f,#threadlist").size()
                + " titleLogin=" + (title.contains("登录") || title.contains("log in"))
                + " titleVerify=" + (title.contains("just a moment") || title.contains("verify") || title.contains("验证码") || title.contains("人机验证"))
                + " titleError=" + (title.matches(".*\\b[45][0-9]{2}\\b.*") || title.contains("access denied") || title.contains("bad gateway") || title.contains("service unavailable"))
                + " explicitEmpty=" + ForumPageGuard.explicitEmpty(doc));
    }
    public static void images(String stage, java.util.List<String> urls) {
        emit("stage=" + stage + " images=" + (urls == null ? 0 : urls.size()));
        if (urls != null) for (int i = 0; i < Math.min(10, urls.size()); i++)
            emit("stage=" + stage + " imageIndex=" + i + " url=" + safeUrl(urls.get(i)));
    }
    public static void imageHtml(String stage, String html, String pageUrl) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html, pageUrl);
        emit("stage=" + stage + " img=" + doc.select("img").size()
                + " attachments=" + doc.select("a[href*=attachment],ignore_js_op,.attachimg,[id^=aimg_]").size()
                + " lazy=" + doc.select("[comiis_loadimages],[file],[zoomfile],[data-src],[data-original],[data-lazy-src],[srcset],[data-srcset]").size());
        images(stage, PostImages.collect(doc, pageUrl));
    }
    public static void parsed(String stage, int count) { emit("stage=" + stage + " result=success count=" + count); }
    static <T> T parse(String stage, java.util.function.Supplier<T> parser) {
        try { return parser.get(); }
        catch (RuntimeException error) {
            if (!(error instanceof ForumPageGuard.PageFailure)) failure(stage, "parser_exception_" + error.getClass().getSimpleName());
            throw error;
        }
    }

    public static String safeContentType(String value) {
        MediaType type = value == null ? null : MediaType.parse(value);
        if (type == null) return "missing_or_invalid";
        String mime = type.type() + "/" + type.subtype();
        if (!mime.matches("text/(html|plain)|application/(xhtml\\+xml|json|xml)|image/[a-z0-9.+-]{1,40}")) return "other";
        return mime;
    }
    public static String safeUrl(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value);
        if (url == null) return "invalid";
        if (!url.host().equals("bbs.binmt.cc") && !url.host().equals("cdn.binmt.cc")) return "external_url_redacted";
        String path = url.encodedPath();
        if (!path.matches("/(forum|home|member|search)\\.php|/|/(thread|forum)-[0-9]+-[0-9]+(?:-[0-9]+)?\\.html")) path = "/[redacted]";
        StringBuilder result = new StringBuilder(url.scheme()).append("://").append(url.host()).append(path);
        boolean first = true;
        for (String name : new String[]{"mod", "view", "fid", "tid", "page", "mobile"}) {
            String parameter = url.queryParameter(name);
            if (parameter == null) continue;
            boolean allowed = (name.equals("mod") && parameter.matches("guide|forumdisplay|viewthread|logging"))
                    || (name.equals("view") && parameter.matches("newthread|hot|digest|new"))
                    || (name.matches("fid|tid|page|mobile") && parameter.matches("[0-9]{1,10}"));
            if (!allowed) continue;
            result.append(first ? '?' : '&').append(name).append('=').append(parameter);
            first = false;
        }
        return result.toString();
    }
}
