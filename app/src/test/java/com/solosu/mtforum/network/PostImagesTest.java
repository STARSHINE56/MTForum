package com.solosu.mtforum.network;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.Test;

public class PostImagesTest {
    private static final String PAGE = "https://bbs.binmt.cc/forum.php?mod=viewthread&tid=123&mobile=2";
    private PostImages.Image image(String html) { return PostImages.read(Jsoup.parse(html).selectFirst("img"), PAGE); }
    @Test public void discuzWrapperPreservesAttachmentAndLeavesBodyHint() {
        List<String> urls = new ArrayList<>();
        String body = PostImages.separate("before<ignore_js_op><img id='aimg_42' file='data/attachment/forum/surface_icon.jpg' src='static/image/common/none.gif'></ignore_js_op>after", PAGE, urls);
        assertEquals(List.of("https://bbs.binmt.cc/data/attachment/forum/surface_icon.jpg"), urls);
        assertTrue(body.contains("before")); assertTrue(body.contains("after")); assertTrue(body.contains("图片 1"));
    }
    @Test public void wordsInNormalFilenameDoNotExcludePhoto() {
        for (String name : List.of("face", "icon", "mini", "stamp", "loading", "magic", "common_", "smiley")) {
            assertEquals(PostImages.Kind.BODY, image("<img src='https://cdn.example.com/photos/" + name + ".jpg'>").kind);
        }
    }
    @Test public void lazyAttributesBeatPlaceholder() {
        for (String attr : List.of("comiis_loadimages", "file", "zoomfile", "data-original", "data-src", "data-file", "data-lazy-src", "data-actualsrc", "data-url", "data-lazy")) {
            assertEquals("https://cdn.binmt.cc/a.jpg", image("<img src='static/image/common/none.gif' " + attr + "='//cdn.binmt.cc/a.jpg'>").url);
        }
    }
    @Test public void invalidLazySourceFallsBackToSafeOriginal() {
        assertEquals("https://bbs.binmt.cc/a.jpg", image("<img file='javascript:alert(1)' src='/a.jpg'>").url);
    }
    @Test public void srcsetSelectsLargestAvailable() {
        assertEquals("https://bbs.binmt.cc/b.webp", image("<img src='static/image/common/none.gif' data-srcset='/a.webp 240w, /b.webp 960w'>").url);
        assertEquals("https://bbs.binmt.cc/b.webp", image("<img src='/small.webp' srcset='/a.webp 1x, /b.webp 2x'>").url);
    }
    @Test public void attachmentLinkFallbackRetainsQuery() {
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=42", image("<a href='forum.php?mod=attachment&amp;aid=42'><img id='aimg_42' src='none.gif'></a>").url);
    }
    @Test public void emojiRemainInlineAndAvatarIsNotGalleryImage() {
        String html = "<img smilieid='1' src='/static/image/smiley/default/smile.gif'>"
                + "<div class='avt'><img src='/profile-photo.jpg'></div><img src='/photo.jpg'>";
        List<String> urls = new ArrayList<>();
        String body = PostImages.separate(html, PAGE, urls);
        assertEquals(List.of("https://bbs.binmt.cc/photo.jpg"), urls);
        assertTrue(body.contains("https://bbs.binmt.cc/static/image/smiley/default/smile.gif"));
        assertFalse(body.contains("profile-photo.jpg"));
    }
    @Test public void placeholdersAndUnsafeSourcesDoNotDisappearSilently() {
        List<String> urls = new ArrayList<>();
        String body = PostImages.separate("<img src='none.gif'><img src='data:image/png;base64,AAAA'><img>", PAGE, urls);
        assertTrue(urls.isEmpty()); assertEquals(3, Jsoup.parse(body).select("span").size()); assertTrue(body.contains("图片地址未解析"));
    }
    @Test public void relativeQueryAndProtocolAreNormalized() {
        assertEquals("https://cdn.binmt.cc/a.jpg?token=private&x=1", image("<img src='http://cdn.binmt.cc/a.jpg?token=private&amp;x=1#preview'>").url);
        assertNull(ForumPageGuard.imageUrl("https://name:password@cdn.example.com/a.jpg", PAGE));
        assertEquals("https://bbs.binmt.cc/path/a.jpg", ForumPageGuard.imageUrl("./a.jpg", "https://bbs.binmt.cc/path/page.html"));
    }
    @Test public void duplicateUrlsUseOneGallerySlot() {
        List<String> urls = new ArrayList<>();
        String body = PostImages.separate("<img file='/a.jpg'><img data-src='/a.jpg'>", PAGE, urls);
        assertEquals(1, urls.size()); assertEquals(2, Jsoup.parse(body).select("span").size());
    }
    @Test public void detailAndListUseSameSourcesAndClassification() {
        String images = "<img class='top_tximg' src='/avatar.jpg'><img data-lazy-src='/surface_icon.jpg' src='none.gif'>";
        String detail = "<div class='comiis_viewtit'><h2><div class='km_tits'>sample</div></h2></div><div class='comiis_postli' id='pid1'><div class='comiis_message'><div class='comiis_messages'>" + images + "</div></div></div>";
        assertEquals(List.of("https://bbs.binmt.cc/surface_icon.jpg"), ForumParser.parseThreadDetail(detail).getImageUrls());
        String list = "<li class='forumlist_li'><div class='forumlist_li_top'></div><div class='mmlist_li_box'><div class='list_body'><a class='list_body_title' href='thread-123-1-1.html'>sample</a></div><div class='comiis_pyqlist_imgs'>" + images + "</div></div></li>";
        assertEquals(List.of("https://bbs.binmt.cc/surface_icon.jpg"), ForumParser.parseThreadList(list).get(0).getImageUrls());
    }
    @Test public void listAuthorBadgeIsNotPostThumbnail() {
        String html = "<li class='forumlist_li'><div class='forumlist_li_top'><img src='/rank.png'></div><div class='mmlist_li_box'><div class='list_body'><a class='list_body_title' href='thread-123-1-1.html'>sample</a></div><img src='/actual.jpg'></div></li>";
        assertEquals("https://bbs.binmt.cc/actual.jpg", ForumParser.parseThreadList(html).get(0).getThumbnailUrl());
    }
}
