package com.solosu.mtforum.network;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class ForumImageNetworkTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
    private static MockResponse png() { return new MockResponse().setHeader("Content-Type", "image/png").setBody(new okio.Buffer().write(PNG)); }
    private static MockResponse challenge(int status) { return new MockResponse().setResponseCode(status).setHeader("Content-Type", "text/html").setBody("<html><script>var arg1='challenge';location.reload();</script></html>"); }
    private Request image(MockWebServer server) {
        return new Request.Builder().url(server.url("/photo.jpg"))
                .header("User-Agent", ForumNetwork.MOBILE_UA).header("Referer", server.url("/forum.php?mod=viewthread&tid=123&mobile=2").toString())
                .tag(ForumImageInterceptor.Context.class, new ForumImageInterceptor.Context(server.url("/forum.php?mod=viewthread&tid=123&mobile=2").toString(), "session1")).build();
    }
    private OkHttpClient client(ForumImageInterceptor interceptor, CookieJar jar) {
        return new OkHttpClient.Builder().cookieJar(jar).followRedirects(false).addInterceptor(interceptor).build();
    }
    @Test public void freshCookiesUaRefererAndOriginalImageReplay() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(challenge(403)); server.enqueue(png()); server.start();
            List<Cookie> cookies = new ArrayList<>(); AtomicInteger calls = new AtomicInteger();
            CookieJar jar = new CookieJar() {
                public void saveFromResponse(HttpUrl u, List<Cookie> c) {}
                public List<Cookie> loadForRequest(HttpUrl u) { return new ArrayList<>(cookies); }
            };
            ForumImageInterceptor interceptor = new ForumImageInterceptor(page -> {
                assertEquals("/forum.php", page.url().encodedPath()); assertEquals("123", page.url().queryParameter("tid"));
                assertEquals(ForumNetwork.MOBILE_UA, page.header("User-Agent"));
                cookies.add(new Cookie.Builder().name("clearance").value("secret").hostOnlyDomain("localhost").build()); calls.incrementAndGet(); return true;
            }, u -> u.startsWith(server.url("/").toString()));
            try (Response response = client(interceptor, jar).newCall(image(server)).execute()) { assertArrayEquals(PNG, response.body().bytes()); }
            RecordedRequest first = server.takeRequest(), second = server.takeRequest();
            assertEquals(first.getPath(), second.getPath()); assertNull(first.getHeader("Cookie")); assertEquals("clearance=secret", second.getHeader("Cookie"));
            assertEquals(first.getHeader("Referer"), second.getHeader("Referer")); assertEquals(1, calls.get());
        }
    }
    @Test public void imagesShareRecentVerificationInsteadOfOpeningPerImage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(); AtomicInteger calls = new AtomicInteger();
            ForumImageInterceptor interceptor = new ForumImageInterceptor(page -> { calls.incrementAndGet(); return true; }, u -> true);
            OkHttpClient client = client(interceptor, CookieJar.NO_COOKIES);
            for (int i=0;i<3;i++) { server.enqueue(challenge(200)); server.enqueue(png()); try(Response r=client.newCall(image(server)).execute()){ assertEquals(200,r.code()); } }
            assertEquals(1, calls.get()); assertEquals(6, server.getRequestCount());
        }
    }
    @Test public void failedVerificationStopsReplayAndIsCooledDown() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(); AtomicInteger calls = new AtomicInteger();
            OkHttpClient client = client(new ForumImageInterceptor(page -> { calls.incrementAndGet(); return false; }, u -> true), CookieJar.NO_COOKIES);
            for(int i=0;i<2;i++){server.enqueue(challenge(503)); assertThrows(IOException.class,()->client.newCall(image(server)).execute());}
            assertEquals(1,calls.get()); assertEquals(2,server.getRequestCount());
        }
    }
    @Test public void stillChallengedAfterReplayDoesNotLoop() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();server.enqueue(challenge(200));server.enqueue(challenge(200)); AtomicInteger calls=new AtomicInteger();
            OkHttpClient client=client(new ForumImageInterceptor(page->{calls.incrementAndGet();return true;},u->true),CookieJar.NO_COOKIES);
            assertThrows(IOException.class,()->client.newCall(image(server)).execute());assertEquals(2,server.getRequestCount());assertEquals(1,calls.get());
        }
    }
    @Test public void independentCdnNeverStartsForumVerification() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();server.enqueue(challenge(403));AtomicInteger calls=new AtomicInteger();
            OkHttpClient client=client(new ForumImageInterceptor(page->{calls.incrementAndGet();return true;}),CookieJar.NO_COOKIES);
            assertThrows(IOException.class,()->client.newCall(image(server)).execute());assertEquals(0,calls.get());
        }
    }
    @Test public void redirectsRecomputeCookieScopeAndDropInjectedHeaders() throws Exception {
        try(MockWebServer origin=new MockWebServer();MockWebServer cdn=new MockWebServer()) {
            origin.start();cdn.start();HttpUrl next=cdn.url("/image.png").newBuilder().host("127.0.0.1").build();
            origin.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",next));cdn.enqueue(png());
            Cookie privateCookie=new Cookie.Builder().name("auth").value("private").hostOnlyDomain("localhost").build();
            CookieJar jar=new CookieJar(){public void saveFromResponse(HttpUrl u,List<Cookie> c){}public List<Cookie> loadForRequest(HttpUrl u){return privateCookie.matches(u)?List.of(privateCookie):List.of();}};
            OkHttpClient client=client(new ForumImageInterceptor(page->false),jar);
            Request req=image(origin).newBuilder().header("Cookie","injected=secret").header("Authorization","secret").build();
            try(Response r=client.newCall(req).execute()){assertEquals(200,r.code());}
            assertEquals("auth=private",origin.takeRequest().getHeader("Cookie"));RecordedRequest cross=cdn.takeRequest();assertNull(cross.getHeader("Cookie"));assertNull(cross.getHeader("Authorization"));assertEquals(ForumNetwork.MOBILE_UA,cross.getHeader("User-Agent"));
        }
    }
    @Test public void missingMimeBinaryIsAllowedButHtmlAndStatusErrorsAreDistinct() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();List<String> logs=new ArrayList<>();ForumDiagnostics.setSink(logs::add);
            OkHttpClient client=client(new ForumImageInterceptor(page->false),CookieJar.NO_COOKIES);
            try {
                server.enqueue(new MockResponse().setBody("binary"));try(Response r=client.newCall(image(server)).execute()){assertEquals("binary",r.body().string());}
                server.enqueue(new MockResponse().setHeader("Content-Type","text/html").setBody("<h1>not image</h1>"));assertThrows(IOException.class,()->client.newCall(image(server)).execute());
                server.enqueue(new MockResponse().setResponseCode(403).setHeader("Content-Type","text/html").setBody("forbidden-secret"));assertThrows(IOException.class,()->client.newCall(image(server)).execute());
                server.enqueue(new MockResponse().setResponseCode(500));assertThrows(IOException.class,()->client.newCall(image(server)).execute());
                String all=String.join("\n",logs);assertTrue(all.contains("status=403"));assertTrue(all.contains("contentType=text/html"));assertTrue(all.contains("non_image_response"));assertTrue(all.contains("http_500"));assertFalse(all.contains("forbidden-secret"));
            } finally {ForumDiagnostics.setSink(null);}
        }
    }
    @Test public void redirectLoopHasFiniteBound() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start();for(int i=0;i<7;i++)server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","/loop"));
            OkHttpClient client=client(new ForumImageInterceptor(page->false),CookieJar.NO_COOKIES);
            assertThrows(IOException.class,()->client.newCall(image(server)).execute());assertEquals(6,server.getRequestCount());
        }
    }
    @Test public void modelCacheIsIsolatedByAccountAndLogsAreRedacted() {
        ForumImages.ImageRequest a=new ForumImages.ImageRequest("https://bbs.binmt.cc/forum.php?mod=attachment&aid=private&token=secret",ForumPageGuard.BASE_URL,"session1");
        ForumImages.ImageRequest b=new ForumImages.ImageRequest(a.url,a.referer,"session2");
        assertNotEquals(a,b);assertFalse(a.toString().contains("secret"));assertNull(a.httpRequest().header("Cookie"));assertNull(a.httpRequest().tag(VerificationInterceptor.PageRequest.class));
    }
    @Test public void concurrentImagesShareOneBrowserRecovery() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            AtomicInteger verifications=new AtomicInteger();java.util.concurrent.atomic.AtomicBoolean cleared=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.CountDownLatch challenged=new java.util.concurrent.CountDownLatch(2);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                if(cleared.get())return png();challenged.countDown();return challenge(403);
            }});server.start();
            OkHttpClient client=client(new ForumImageInterceptor(page->{
                verifications.incrementAndGet();try{assertTrue(challenged.await(3,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){return false;}
                cleared.set(true);return true;
            },u->true),CookieJar.NO_COOKIES);
            java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<Integer> load=()->{try(Response r=client.newCall(image(server)).execute()){return r.code();}};
                java.util.concurrent.Future<Integer> a=workers.submit(load),b=workers.submit(load);
                assertEquals(Integer.valueOf(200),a.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(Integer.valueOf(200),b.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,verifications.get());
            } finally {workers.shutdownNow();}
        }
    }
    @Test public void accountChangeDoesNotReusePreviousImageRecovery() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start();AtomicInteger count=new AtomicInteger();OkHttpClient client=client(new ForumImageInterceptor(page->{count.incrementAndGet();return true;},u->true),CookieJar.NO_COOKIES);
            for(String session:List.of("account1","account2")){
                server.enqueue(challenge(200));server.enqueue(png());
                Request request=image(server).newBuilder().tag(ForumImageInterceptor.Context.class,new ForumImageInterceptor.Context(server.url("/forum.php?mod=viewthread&tid=123").toString(),session)).build();
                try(Response r=client.newCall(request).execute()){assertEquals(200,r.code());}
            }
            assertEquals(2,count.get());
        }
    }
}
