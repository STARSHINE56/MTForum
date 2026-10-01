package com.solosu.mtforum.network;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ForumLoadingTest {
    private String list(String link) {
        return "<div class='comiis_forumlist'><ul><li class='forumlist_li'><div class='mmlist_li_box'><h2><a href='"
                + link + "'>测试标题</a></h2></div></li></ul></div>";
    }

    @Test public void normalMobileList() {
        assertEquals("123", ForumParser.parseThreadList(list("thread-123-1-1.html")).get(0).getTid());
    }
    @Test public void queryThreadLinksAndPagination() {
        assertEquals("123", ForumParser.parseThreadList(list("forum.php?mod=viewthread&tid=123&page=2")).get(0).getTid());
        assertEquals("456", ForumParser.parseForumThreadList(list("thread-456-3-1.html")).get(0).getTid());
    }
    @Test public void changedOuterClassAndDuplicateLinks() {
        String html = "<ul><li><a href='thread-123-1-1.html'>测试</a><a href='thread-123-2-1.html'>2</a></li></ul>";
        assertEquals(1, ForumParser.parseThreadList(html).size());
    }
    @Test public void explicitEmptyList() {
        assertTrue(ForumParser.parseThreadList("<div class='comiis_notip'>暂无帖子</div>").isEmpty());
        assertTrue(ForumParser.parseForumThreadList("<div id='threadlist'><div class='emp'>没有相关帖子</div></div>").isEmpty());
    }
    @Test public void unrecognizedAndMalformedListsFail() {
        assertThrows(IllegalStateException.class, () -> ForumParser.parseThreadList("<h1>new layout</h1>"));
        assertThrows(IllegalStateException.class, () -> ForumParser.parseForumThreadList(list("thread-bad-1-1.html")));
    }
    @Test public void missingAndErrorPagesFail() {
        assertThrows(IllegalStateException.class, () -> ForumParser.parseThreadList(null));
        assertThrows(IllegalStateException.class, () -> ForumParser.parseThreadDetail(" "));
        assertThrows(IllegalStateException.class, () -> ForumParser.parseThreadDetail("<title>503 Service Unavailable</title>"));
    }
    @Test public void loginAndVerificationAreDistinct() {
        IllegalStateException login = assertThrows(IllegalStateException.class,
                () -> ForumParser.parseThreadList("<form action='member.php?mod=logging'><input type='password'></form>"));
        assertTrue(login.getMessage().contains("登录"));
        IllegalStateException verify = assertThrows(IllegalStateException.class,
                () -> ForumParser.parseThreadList("<title>Just a moment...</title><form id='challenge-form'></form>"));
        assertTrue(verify.getMessage().contains("验证"));
    }
    @Test public void publicContentWithLoginLinkIsReadable() {
        assertEquals(1, ForumParser.parseThreadList("<a href='member.php?mod=logging'>登录</a>" + list("thread-12-1-1.html")).size());
    }
    @Test public void detailAndLazyImages() {
        String html = "<div class='comiis_viewtit'><h2><div class='km_tits'>帖子</div></h2></div>"
                + "<div class='comiis_postli' id='pid12'><div class='comiis_message'><div class='comiis_messages'>正文"
                + "<img src='none.gif' comiis_loadimages='//cdn.binmt.cc/attachments/a.jpg'></div></div></div>";
        assertNotNull(ForumParser.parseThreadDetail(html).getContentHtml());
        assertTrue(ForumParser.parseThreadDetail(html).getImageUrls().contains("https://cdn.binmt.cc/attachments/a.jpg"));
    }
    @Test public void unknownDetailFailsInsteadOfBlank() {
        assertThrows(IllegalStateException.class, () -> ForumParser.parseThreadDetail("<div>unknown template</div>"));
    }
    @Test public void imageUrlsResolveAgainstPageAndRejectUnsafeSchemes() {
        String page = "https://bbs.binmt.cc/path/page.html";
        assertEquals("https://bbs.binmt.cc/a.jpg", ForumPageGuard.imageUrl("../a.jpg", page));
        assertEquals("https://bbs.binmt.cc/path/a.jpg", ForumPageGuard.imageUrl(" ./a.jpg ", page));
        assertEquals("https://cdn.binmt.cc/a.jpg", ForumPageGuard.imageUrl("//cdn.binmt.cc/a.jpg", page));
        assertEquals("https://cdn.binmt.cc/a.jpg", ForumPageGuard.imageUrl("http://cdn.binmt.cc/a.jpg", page));
        assertNull(ForumPageGuard.imageUrl("javascript:alert(1)", page));
        assertNull(ForumPageGuard.imageUrl("data:image/png;base64,AAAA", page));
        assertNull(ForumPageGuard.imageUrl("", page));
    }
    @Test public void allFailureStatusesAreRejected() throws Exception {
        ResponsePolicy.checkStatus(200);
        ResponsePolicy.checkStatus(204);
        for (int status : new int[]{302,401,403,404,429,500,502,503}) {
            IOException failure = assertThrows(IOException.class, () -> ResponsePolicy.checkStatus(status));
            assertTrue(failure.getMessage().contains(Integer.toString(status)));
        }
    }
    @Test public void unknownCommunityPageFails() {
        assertThrows(IllegalStateException.class, () -> ForumParser.parseCommunityPage("<div>unknown template</div>"));
    }
    @Test public void detailFallbackKeepsText() {
        assertTrue(ForumParser.parseThreadDetail("<table><tr><td class='t_f'>正文仍然可读</td></tr></table>")
                .getContentHtml().contains("正文仍然可读"));
    }
    @Test public void timeoutIsReadable() {
        assertTrue(ResponsePolicy.errorMessage(new java.net.SocketTimeoutException()).contains("超时"));
        assertTrue(ResponsePolicy.errorMessage(new java.net.UnknownHostException()).contains("地址"));
    }
}
