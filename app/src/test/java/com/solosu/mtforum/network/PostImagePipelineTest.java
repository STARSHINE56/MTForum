package com.solosu.mtforum.network;

import static org.junit.Assert.*;
import com.solosu.mtforum.util.BBCodeUtil;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.Test;

/** Constructed regression cases, NOT the unavailable live thread 173805 HTML. */
public class PostImagePipelineTest {
    private static final String PAGE = "https://bbs.binmt.cc/thread-173805-1-1.html";
    @Test public void fallbackBodyKeepsImages() {
        var detail = ForumParser.parseThreadDetail("<div class='postbody'>text<img data-src='/photo.jpg'></div>");
        assertEquals(List.of("https://bbs.binmt.cc/photo.jpg"), detail.getImageUrls());
    }
    @Test public void desktopBodyAndSiblingAttachmentAreBothCollected() {
        var detail = ForumParser.parseThreadDetail("<div id='post_1' class='post'><table><tr><td class='t_f'>text<img src='/one.jpg'></td></tr></table><div id='postattach_1'><ignore_js_op><img file='/two.jpg'></ignore_js_op></div></div>");
        assertEquals(List.of("https://bbs.binmt.cc/one.jpg", "https://bbs.binmt.cc/two.jpg"), detail.getImageUrls());
    }
    @Test public void normalPathDoesNotRestrictCollectionToComiisMessages() {
        var detail = ForumParser.parseThreadDetail("<div class='comiis_postli'><div class='comiis_message'><div class='comiis_messages'>text<img src='/one.jpg'></div><img zoomfile='/two.jpg'></div><div class='comiis_attach'><img file='/three.jpg'></div></div>");
        assertEquals(3, detail.getImageUrls().size());
    }
    @Test public void attachmentOnlyPostShowsGalleryWithEmptyBody() {
        var detail = ForumParser.parseThreadDetail("<div class='comiis_postli'><div class='attachimg'><a href='/photo.webp'>image</a></div></div>");
        assertEquals(1, detail.getImageUrls().size());
        assertTrue(PostImages.showGallery(detail.getContentHtml(), detail.getImageUrls()));
        assertTrue(PostImages.showGallery("", detail.getImageUrls()));
        assertFalse(PostImages.showGallery("text", List.of()));
    }
    @Test public void replyImagesAreNotMixedIntoOpGallery() {
        var detail = ForumParser.parseThreadDetail("<div class='comiis_postli'><div class='comiis_message'>op<img src='/op.jpg'></div></div><div class='comiis_postli'><div class='comiis_message'><img src='/reply.jpg'></div></div>");
        assertEquals(List.of("https://bbs.binmt.cc/op.jpg"), detail.getImageUrls());
    }
    @Test public void unresolvedImageRetainsOriginalTagAndAttributes() {
        String result = PostImages.separate("<ignore_js_op><img id='aimg_99' data-source='unknown'></ignore_js_op>", PAGE, new ArrayList<>());
        assertEquals("unknown", Jsoup.parse(result).selectFirst("img").attr("data-source"));
        assertTrue(result.contains("图片地址未解析"));
    }
    @Test public void bbcodePreservesExistingImageAttributeBracketsAndEscapesNewUrl() {
        String html = "<img data-src='/photo[name].jpg'>[img]/other.jpg?a=1&b=2\"x[/img]";
        String converted = BBCodeUtil.convertBBCodeToHtml(html);
        assertEquals("/photo[name].jpg", Jsoup.parse(converted).select("img").get(0).attr("data-src"));
        assertEquals("/other.jpg?a=1&b=2\"x", Jsoup.parse(converted).select("img").get(1).attr("src"));
        assertEquals(2, PostImages.collect(Jsoup.parse(converted), PAGE).size());
    }
    @Test public void diagnosticsRedactImageSecretsAndCountPipeline() {
        List<String> lines = new ArrayList<>(); ForumDiagnostics.setSink(lines::add);
        try {
            ForumDiagnostics.imageHtml("test", "<img file='/SECRET.jpg?token=SECRET'><a href='forum.php?mod=attachment&aid=SECRET'>SECRET</a>", PAGE);
            String result = String.join("\n", lines);
            assertTrue(result.contains("img=1 attachments=1 lazy=1"));
            assertTrue(result.contains("images=1")); assertFalse(result.contains("SECRET"));
        } finally { ForumDiagnostics.setSink(null); }
    }
}
