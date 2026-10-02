package com.solosu.mtforum.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** One DOM-based image policy for lists, post bodies, hidden content and replies. */
public final class PostImages {
    private PostImages() {}
    public enum Kind { BODY, EMOJI, AVATAR, PLACEHOLDER, INVALID }
    public static final class Image {
        public final String url;
        public final Kind kind;
        Image(String url, Kind kind) { this.url = url; this.kind = kind; }
    }
    private static final String[] SOURCES = {"comiis_loadimages", "zoomfile", "file", "data-original",
            "data-src", "data-file", "data-lazy-src", "data-actualsrc", "data-lazy", "data-url", "src"};
    public static Image read(Element image, String pageUrl) {
        String candidate = null;
        for (String name : SOURCES) {
            if (name.equals("src")) {
                String responsive = srcset(image, pageUrl);
                if (responsive != null) { candidate = responsive; break; }
            }
            String url = ForumPageGuard.imageUrl(image.attr(name), pageUrl);
            if (url != null && !placeholder(url)) { candidate = url; break; }
        }
        if (candidate == null) candidate = srcset(image, pageUrl);
        // Discuz may put the original image in an attachment link instead of src.
        if (candidate == null && image.id().matches("(?i)aimg_\\d+")) {
            Element parent = image.parent();
            if (parent != null && parent.tagName().equals("a")) {
                String url = ForumPageGuard.imageUrl(parent.attr("href"), pageUrl);
                HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
                if (parsed != null && (parsed.encodedPath().matches("(?i).*\\.(png|jpg|jpeg|gif|webp)$")
                        || (parsed.encodedPath().equals("/forum.php") && "attachment".equals(parsed.queryParameter("mod"))))) candidate = url;
            }
        }
        if (candidate == null) {
            String src = ForumPageGuard.imageUrl(image.attr("src"), pageUrl);
            return new Image(null, src != null && placeholder(src) ? Kind.PLACEHOLDER : Kind.INVALID);
        }
        HttpUrl url = HttpUrl.get(candidate);
        String path = url.encodedPath().toLowerCase(Locale.ROOT);
        if (image.hasAttr("smilieid") || image.hasClass("smilie") || image.hasClass("smiley")
                || path.startsWith("/static/image/smiley/")) return new Image(candidate, Kind.EMOJI);
        if (image.hasClass("top_tximg") || image.hasClass("avatar") || image.hasClass("msg_avt")
                || path.equals("/uc_server/avatar.php") || path.equals("/avatar.php")
                || path.startsWith("/uc_server/data/avatar/")
                || (path.equals("/uc_server/index.php") && "avatar".equals(url.queryParameter("a")))) return new Image(candidate, Kind.AVATAR);
        // DOM context is stronger evidence than words somewhere in an attachment filename.
        for (Element parent = image.parent(); parent != null; parent = parent.parent()) {
            if (parent.hasClass("avt") || parent.hasClass("postli_top_tximg") || parent.hasClass("user_img")) return new Image(candidate, Kind.AVATAR);
        }
        return new Image(candidate, Kind.BODY);
    }
    private static boolean placeholder(String value) {
        String path = HttpUrl.get(value).encodedPath().toLowerCase(Locale.ROOT);
        return path.matches("/(?:static/image/(?:common|mobile)/|template/comiis_app/(?:[^/]+/)*)?(?:none|loading|blank|spacer|transparent)\\.(?:gif|png)");
    }
    private static String srcset(Element image, String pageUrl) {
        String best = null;
        double largest = -1;
        for (String attr : new String[]{"data-srcset", "srcset"}) {
            for (String entry : image.attr(attr).split(",")) {
                String[] parts = entry.trim().split("\\s+");
                String url = ForumPageGuard.imageUrl(parts[0], pageUrl);
                if (url == null || placeholder(url)) continue;
                double size = 1;
                if (parts.length > 1 && parts[1].matches("[0-9.]+[wx]")) {
                    try { size = Double.parseDouble(parts[1].substring(0, parts[1].length() - 1)); }
                    catch (NumberFormatException ignored) { continue; }
                }
                if (size > largest) { largest = size; best = url; }
            }
        }
        return best;
    }
    public static List<String> collect(Element root, String pageUrl) {
        List<String> urls = new ArrayList<>();
        if (root == null) return urls;
        for (Element element : root.select("img")) {
            Image image = read(element, pageUrl);
            if (image.kind == Kind.BODY && !urls.contains(image.url)) urls.add(image.url);
        }
        // Only explicit image attachment links; never treat arbitrary downloadable files as images.
        for (Element link : root.select("a[href]")) {
            String url = ForumPageGuard.imageUrl(link.attr("href"), pageUrl);
            HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
            if (parsed != null && parsed.encodedPath().matches("(?i).*\\.(png|jpg|jpeg|gif|webp)$")
                    && (link.closest("ignore_js_op, .attachimg, .attm, .comiis_attach, [id^=aimg_]" ) != null)
                    && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }
    /** Body plus explicitly associated attachment areas, bounded to this post (not replies). */
    public static List<String> collectPost(Element body, Element post, String pageUrl) {
        List<String> urls = collect(body, pageUrl);
        if (post != null) {
            for (Element region : post.select("ignore_js_op, .attachimg, .attm, .comiis_attach, .comiis_postattach, [id^=postattach_], [id^=aimg_]")) {
                // A nested reply/post belongs to a different author.
                Element owner = region.closest(".comiis_postli, [id^=post_], article");
                if (owner != null && owner != post && post.select(".comiis_postli, [id^=post_], article").contains(owner)) continue;
                for (String url : collect(region, pageUrl)) if (!urls.contains(url)) urls.add(url);
            }
        }
        return urls;
    }
    public static boolean showGallery(String html, List<String> urls) {
        return urls != null && !urls.isEmpty();
    }
    public static String separate(String html, String pageUrl, List<String> urls) {
        if (html == null || html.isEmpty()) return "";
        Document doc = Jsoup.parseBodyFragment(html, pageUrl);
        doc.select("script,style").remove();
        // ignore_js_op contains attachment images; unwrap instead of deleting its subtree.
        for (Element wrapper : doc.select("ignore_js_op")) wrapper.unwrap();
        for (Element element : doc.select("img")) {
            Image image = read(element, pageUrl);
            if (image.kind == Kind.BODY) {
                if (!urls.contains(image.url)) urls.add(image.url);
                Element hint = new Element("span");
                hint.text("[图片 " + (urls.indexOf(image.url) + 1) + "，见图片区域]");
                element.replaceWith(hint);
            } else if (image.kind == Kind.EMOJI) element.attr("src", image.url);
            else if (image.kind == Kind.AVATAR) element.remove();
            else {
                Element hint = new Element("span");
                hint.text("[图片地址未解析，请查看原帖]");
                element.after(hint); // Preserve unresolved original attributes for inline fallback/diagnosis.
                ForumDiagnostics.failure("image_parse", "missing_or_unsafe_source");
            }
        }
        return doc.body().html();
    }
}
