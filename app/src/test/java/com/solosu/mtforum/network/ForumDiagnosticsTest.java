package com.solosu.mtforum.network;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;
import okhttp3.Response;

public class ForumDiagnosticsTest {
    @After public void resetSink() { ForumDiagnostics.setSink(null); }
    @Test public void urlsStripCredentialsUnknownParametersAndFragments() {
        String result = ForumDiagnostics.safeUrl("https://user:SECRET@bbs.binmt.cc/forum.php?mod=viewthread&tid=172677&token=SECRET&formhash=SECRET&uid=999#SECRET");
        assertEquals("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=172677", result);
        assertEquals("https://bbs.binmt.cc/[redacted]", ForumDiagnostics.safeUrl("https://bbs.binmt.cc/SECRET/SECRET.png"));
        assertEquals("external_url_redacted", ForumDiagnostics.safeUrl("https://SECRET.example/SECRET?token=SECRET"));
    }
    @Test public void contentTypeParametersAndUnknownTypesAreNotLogged() {
        assertEquals("text/html", ForumDiagnostics.safeContentType("text/html; token=SECRET"));
        assertEquals("other", ForumDiagnostics.safeContentType("application/SECRET"));
        assertEquals("missing_or_invalid", ForumDiagnostics.safeContentType(null));
    }
    @Test public void structureLogsNeverIncludeHtmlTextAttributesOrErrorMessages() {
        List<String> lines = new ArrayList<>(); ForumDiagnostics.setSink(lines::add);
        ForumDiagnostics.html("home_list", "<title>SECRET</title><input value='SECRET'><script>token=SECRET</script><li class='forumlist_li'><a href='thread-42-1-1.html'>SECRET</a></li>");
        ForumDiagnostics.transportFailure(new java.net.UnknownHostException("token=SECRET"));
        String result = String.join("\n", lines);
        assertTrue(result.contains("li=1")); assertTrue(result.contains("threadLinks=1"));
        assertTrue(result.contains("reason=dns")); assertFalse(result.contains("SECRET"));
    }
    @Test public void guardReportsFailureStageAndReason() {
        ForumPageGuard.PageFailure error = assertThrows(ForumPageGuard.PageFailure.class,
                () -> ForumParser.parseForumThreadList("<div>unknown</div>"));
        assertEquals("forum_list", error.stage); assertEquals("selector_mismatch", error.reason);
        error = assertThrows(ForumPageGuard.PageFailure.class, () -> ForumParser.parseThreadDetail("<title>Just a moment...</title>"));
        assertEquals("thread_detail", error.stage); assertEquals("verification", error.reason);
        error = assertThrows(ForumPageGuard.PageFailure.class, () -> ForumParser.parseThreadList("<form action='member.php?mod=logging'><input type=password></form>"));
        assertEquals("login", error.reason);
    }
    @Test public void brokenLogSinkCannotBreakParsing() {
        ForumDiagnostics.setSink(line -> { throw new IllegalStateException("logger failed"); });
        assertTrue(ForumParser.parseThreadList("<div class='comiis_notip'>暂无帖子</div>").isEmpty());
    }
    @Test public void sharedPageRequestUsesActualMobileHeaders() {
        Request request = ForumNetwork.pageRequest(ForumParser.getHomeUrl(1), ForumNetwork.MOBILE_UA, null);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=guide&view=newthread&page=1&mobile=2", request.url().toString());
        assertEquals(ForumNetwork.MOBILE_UA, request.header("User-Agent"));
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", request.header("Accept-Language"));
        assertNull(request.header("Cookie")); assertNull(request.header("Referer"));
    }
    @Test public void actualTransportRejectsStatusAndKeepsSafeResponseMetadata() throws Exception {
        List<String> lines = new ArrayList<>(); ForumDiagnostics.setSink(lines::add);
        try (ServerSocket server = new ServerSocket(0)) {
            java.lang.Thread worker = new java.lang.Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html; token=SECRET\r\nSet-Cookie: auth=SECRET\r\nContent-Length: 6\r\nConnection: close\r\n\r\nSECRET").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }); worker.start();
            Request request = ForumNetwork.pageRequest("http://127.0.0.1:" + server.getLocalPort() + "/SECRET?token=SECRET", ForumNetwork.MOBILE_UA, null);
            assertThrows(java.io.IOException.class, () -> { try (Response ignored = ForumNetwork.clientBuilder().build().newCall(request).execute()) {} });
            worker.join(5000); assertFalse(worker.isAlive());
        }
        String result = String.join("\n", lines);
        assertTrue(result.contains("status=403")); assertTrue(result.contains("contentType=text/html"));
        assertTrue(result.contains("reason=http_403")); assertFalse(result.contains("SECRET"));
    }
}
