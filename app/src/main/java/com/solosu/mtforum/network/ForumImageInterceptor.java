package com.solosu.mtforum.network;

import java.io.IOException;
import java.util.function.Predicate;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Binary requests share cookies and page verification, never verify each image URL in a WebView. */
public final class ForumImageInterceptor implements Interceptor {
    public static final class ImageFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public ImageFailure(String reason) { super("图片加载失败（image_http/" + reason + "）"); }
    }
    public static final class Context {
        public final String pageUrl;
        public final String session;
        public Context(String pageUrl, String session) { this.pageUrl = pageUrl; this.session = session; }
    }
    private final VerificationInterceptor.Verifier verifier;
    private final Predicate<String> trusted;
    private final VerificationCoordinator recovery = new VerificationCoordinator(true);
    public ForumImageInterceptor(VerificationInterceptor.Verifier verifier) { this(verifier, VerificationPolicy::trustedUrl); }
    ForumImageInterceptor(VerificationInterceptor.Verifier verifier, Predicate<String> trusted) {
        this.verifier = verifier;
        this.trusted = trusted;
    }
    @Override public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Context context = original.tag(Context.class);
        Response response = follow(chain, original);
        if (context != null && trusted.test(original.url().toString()) && trusted.test(response.request().url().toString())
                && trusted.test(context.pageUrl) && VerificationInterceptor.isChallenge(response)) {
            ForumDiagnostics.failure("image_http", "verification");
            response.close();
            Request page = ForumNetwork.pageRequest(context.pageUrl, original.header("User-Agent"), null);
            boolean passed = recovery.verify(context.session + "|" + page.url().host() + "|" + page.header("User-Agent"),
                    result -> result.complete(verifier.verify(page)));
            if (!passed) throw failure("verification_incomplete");
            response = follow(chain, original); // one replay, fresh cookies from the shared CookieJar
        }
        try {
            if (response.code() < 200 || response.code() >= 300) throw failure("http_" + response.code());
            String type = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
            if (type.startsWith("text/") || type.contains("html") || type.contains("json") || type.contains("xml")) {
                throw failure(VerificationInterceptor.isChallenge(response) ? "verification_after_replay" : "non_image_response");
            }
            if (response.body() == null || response.body().contentLength() == 0) throw failure("empty_response");
            // Do not reject missing/octet-stream MIME: valid Discuz attachments may use either.
            return response;
        } catch (IOException error) { response.close(); throw error; }
    }
    private Response follow(Chain chain, Request original) throws IOException {
        Request current = original.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build();
        for (int hop = 0; hop <= 5; hop++) {
            Response response;
            ForumDiagnostics.start(current.url().toString());
            try { response = chain.proceed(current); }
            catch (IOException error) { ForumDiagnostics.failure("image_http", ForumDiagnostics.transportReason(error)); throw failure("transport_" + ForumDiagnostics.transportReason(error)); }
            ForumDiagnostics.response(response);
            if (!java.util.Arrays.asList(301,302,303,307,308).contains(response.code())) return response;
            String location = response.header("Location");
            HttpUrl next = location == null ? null : current.url().resolve(location);
            response.close();
            if (next == null || !next.username().isEmpty() || !next.password().isEmpty()) throw failure("invalid_redirect");
            if (original.url().isHttps() && !next.isHttps()) throw failure("https_downgrade");
            current = current.newBuilder().url(next).removeHeader("Cookie").removeHeader("Authorization").build();
        }
        throw failure("redirect_limit");
    }
    private static IOException failure(String reason) {
        ForumDiagnostics.failure("image_http", reason);
        return new ImageFailure(reason);
    }
}
