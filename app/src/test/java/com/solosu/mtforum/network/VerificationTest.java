package com.solosu.mtforum.network;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.Dispatcher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class VerificationTest {
    private static final String CHALLENGE = "<html><title>Just a moment...</title><script>window._cf_chl_opt={};</script></html>";
    private static final String FORUM = "<html><div id='threadlist'><a href='thread-1-1-1.html'>帖子</a></div></html>";

    @Test public void scriptChallengeAndHttpErrorChallengeRecognized() {
        assertTrue(VerificationPolicy.challenge(CHALLENGE, "text/html; charset=utf-8"));
        assertTrue(VerificationPolicy.challenge("<title>安全验证</title><script src='/cdn-cgi/challenge-platform/a.js'></script>", null));
        assertTrue(VerificationPolicy.challenge("<html><title>MT论坛</title><div class='cf-turnstile'></div></html>", "text/html"));
    }
    @Test public void vendorNeutralEsaScriptStubIsRecognized() {
        assertTrue(VerificationPolicy.challenge("<html><script>var arg1='test'; location.reload();</script></html>", "text/html"));
        assertTrue(VerificationPolicy.challenge("<html><body><script src='/security.js'></script>正在检查</body></html>", "text/html"));
        assertFalse(VerificationPolicy.challenge("<html><body><script src='/forum.js'></script><input name='formhash' value='redacted'></body></html>", "text/html"));
    }
    @Test public void forumPostsDiscussingCaptchaAreNotChallenges() {
        assertFalse(VerificationPolicy.challenge("<title>人机验证讨论</title>" + FORUM + CHALLENGE, "text/html"));
        assertTrue(VerificationPolicy.forumContent(FORUM));
        assertFalse(VerificationPolicy.forumContent(CHALLENGE));
    }
    @Test public void loginXmlJsonAndUnknownTemplatesDoNotLaunchWebview() {
        assertFalse(VerificationPolicy.challenge("<form action='member.php?mod=logging'><input type='password'></form>" + CHALLENGE, "text/html"));
        assertFalse(VerificationPolicy.challenge("<root><![CDATA[" + CHALLENGE + "]]></root>", "text/html"));
        assertFalse(VerificationPolicy.challenge(CHALLENGE, "application/json"));
        assertFalse(VerificationPolicy.challenge("<html><title>502 Bad Gateway</title></html>", "text/html"));
        assertFalse(VerificationPolicy.challenge("<html><h1>new template</h1></html>", "text/html"));
    }
    @Test public void urlsAndCookieScopeAreStrict() {
        assertTrue(VerificationPolicy.trustedUrl("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=12"));
        for (String url : new String[]{"http://bbs.binmt.cc/", "https://bbs.binmt.cc.evil.test/", "https://evil.test/", "file:///a", "https://user:pass@bbs.binmt.cc/", "https://bbs.binmt.cc:444/"}) {
            assertFalse(url, VerificationPolicy.trustedUrl(url));
        }
        HttpUrl forum = HttpUrl.get("https://bbs.binmt.cc/forum.php");
        assertEquals("a=b", VerificationCookies.forRequest("auth=a=b; clearance=xyz", forum).get(0).value());
        assertTrue(VerificationCookies.forRequest("clearance=xyz", HttpUrl.get("https://evil.test/")).isEmpty());
        assertFalse(VerificationCookies.forRequest("clearance=xyz", forum).get(0).persistent());
        assertTrue(VerificationCookies.forRequest("clearance=xyz", forum).get(0).hostOnly());
        assertTrue(VerificationCookies.forRequest("clearance=xyz", forum).get(0).secure());
    }
    @Test public void pageGuardAndTransportShareDetection() {
        ForumPageGuard.PageFailure error = assertThrows(ForumPageGuard.PageFailure.class,
                () -> ForumPageGuard.parse("<title>MT论坛</title><script>window._cf_chl_opt={};</script>"));
        assertEquals("verification", error.reason);
    }
    @Test public void concurrentRequestsShareOneVerification() throws Exception {
        VerificationCoordinator coordinator = new VerificationCoordinator(System::currentTimeMillis, 2000, 1000);
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> coordinator.verify(result -> {
                starts.incrementAndGet(); started.countDown(); outcome.thenAccept(result::complete);
            }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
            Future<Boolean> second = executor.submit(() -> { worker.set(Thread.currentThread()); return coordinator.verify(result -> starts.incrementAndGet()); });
            // Wait until the second worker has entered the future wait, not merely started.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((worker.get() == null || worker.get().getState() != Thread.State.TIMED_WAITING)
                    && System.nanoTime() < deadline) Thread.yield();
            assertNotNull(worker.get());
            assertEquals(Thread.State.TIMED_WAITING, worker.get().getState());
            outcome.complete(true);
            assertTrue(first.get(1, TimeUnit.SECONDS));
            assertTrue(second.get(1, TimeUnit.SECONDS));
            assertEquals(1, starts.get());
        } finally { executor.shutdownNow(); }
    }
    @Test public void distinctUserAgentsGetIndependentFlowsWithoutOverlappingBrowsers() throws Exception {
        VerificationCoordinator coordinator = new VerificationCoordinator(System::currentTimeMillis, 2000, 1000);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger starts = new AtomicInteger();
        try {
            Future<Boolean> first = executor.submit(() -> coordinator.verify("mobile-UA", result -> {
                starts.incrementAndGet(); started.countDown(); outcome.thenAccept(result::complete);
            }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
            Future<Boolean> second = executor.submit(() -> {
                worker.set(Thread.currentThread());
                return coordinator.verify("desktop-UA", result -> { assertTrue(outcome.isDone()); starts.incrementAndGet(); result.complete(true); });
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((worker.get() == null || worker.get().getState() != Thread.State.TIMED_WAITING)
                    && System.nanoTime() < deadline) Thread.yield();
            assertNotNull(worker.get());
            assertEquals(Thread.State.TIMED_WAITING, worker.get().getState());
            outcome.complete(true);
            assertTrue(first.get(1, TimeUnit.SECONDS));
            assertTrue(second.get(1, TimeUnit.SECONDS));
            assertEquals(2, starts.get());
        } finally { executor.shutdownNow(); }
    }
    @Test public void cancelledVerificationCoolsDownAndCanRetryLater() {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger starts = new AtomicInteger();
        VerificationCoordinator coordinator = new VerificationCoordinator(now::get, 100, 60000);
        assertFalse(coordinator.verify(result -> { starts.incrementAndGet(); result.complete(false); }));
        assertFalse(coordinator.verify(result -> { starts.incrementAndGet(); result.complete(true); }));
        assertEquals(1, starts.get());
        now.addAndGet(60001);
        assertTrue(coordinator.verify(result -> { starts.incrementAndGet(); result.complete(true); }));
        assertEquals(2, starts.get());
    }
    @Test public void hungVerificationIsBoundedAndCompletesForCleanup() {
        VerificationCoordinator coordinator = new VerificationCoordinator(System::currentTimeMillis, 30, 60000);
        CompletableFuture<CompletableFuture<Boolean>> captured = new CompletableFuture<>();
        assertFalse(coordinator.verify(captured::complete));
        assertTrue(captured.join().isDone());
        assertFalse(captured.join().join());
    }
    @Test public void recoveryRunsBeforeStatusCheckAndRestoresExactGet() throws Exception {
        for (int status : new int[]{200,403,429,503}) runRecovery(status, false, false, false);
    }
    @Test public void unchangedChallengeFailsAfterOneReplay() throws Exception { runRecovery(200, true, false, false); }
    @Test public void probesNeverLaunchAnotherVerification() throws Exception { runRecovery(200, false, true, false); }
    @Test public void postRequestsAreNeverReplayed() throws Exception { runRecovery(200, false, false, true); }
    @Test public void untaggedImageRequestsAreNeverReplayed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody(CHALLENGE));
            AtomicInteger verifies = new AtomicInteger();
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new VerificationInterceptor(
                    request -> { verifies.incrementAndGet(); return true; }, request -> true)).build();
            try (Response response = client.newCall(new Request.Builder().url(server.url("/image.jpg")).build()).execute()) {
                assertEquals(CHALLENGE, response.body().string());
            }
            assertEquals(0, verifies.get());
            assertEquals(1, server.getRequestCount());
        }
    }

    private void runRecovery(int challengeStatus, boolean alwaysChallenge, boolean probe, boolean post) throws Exception {
        MockWebServer server = new MockWebServer();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger verifies = new AtomicInteger();
        AtomicInteger correctHeaders = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                int number = requests.incrementAndGet();
                if ("test-UA".equals(request.getHeader("User-Agent"))
                        && "https://bbs.binmt.cc/".equals(request.getHeader("Referer"))
                        && "mod=viewthread&tid=12".equals(request.getRequestUrl().encodedQuery())) correctHeaders.incrementAndGet();
                boolean challenged = alwaysChallenge || number == 1;
                return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setResponseCode(challenged ? challengeStatus : 200).setBody(challenged ? CHALLENGE : FORUM);
            }
        });
        server.start();
        try {
            VerificationInterceptor interceptor = new VerificationInterceptor(original -> {
                verifies.incrementAndGet(); assertEquals("test-UA", original.header("User-Agent")); return true;
            }, request -> request.url().host().equals("localhost"));
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                try { ResponsePolicy.checkStatus(response.code()); return response; }
                catch (java.io.IOException error) { response.close(); throw error; }
            }).addInterceptor(interceptor).build();
            Request.Builder builder = ForumNetwork.pageRequest(server.url("/forum.php?mod=viewthread&tid=12").toString(), "test-UA", "https://bbs.binmt.cc/").newBuilder();
            if (probe) builder.tag(VerificationInterceptor.Probe.class, VerificationInterceptor.PROBE);
            if (post) builder.post(okhttp3.RequestBody.create(null, "x=1"));
            if (alwaysChallenge) assertThrows(java.io.IOException.class, () -> { try (Response response = client.newCall(builder.build()).execute()) { response.body().string(); } });
            else try (Response response = client.newCall(builder.build()).execute()) {
                assertEquals(probe || post ? CHALLENGE : FORUM, response.body().string());
            }
            assertEquals(probe || post ? 0 : 1, verifies.get());
            assertEquals(probe || post ? 1 : 2, requests.get());
            assertEquals(requests.get(), correctHeaders.get());
        } finally { server.shutdown(); }
    }
}
