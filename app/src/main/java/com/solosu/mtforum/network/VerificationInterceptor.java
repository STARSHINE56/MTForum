package com.solosu.mtforum.network;

import java.io.IOException;
import java.util.function.Predicate;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Recovery wraps the normal response policy so HTML challenges with 403/503 can be handled first. */
public final class VerificationInterceptor implements Interceptor {
    public interface Verifier { boolean verify(Request original); }
    public static final class PageRequest { private PageRequest() {} }
    public static final class Probe { private Probe() {} }
    static final PageRequest PAGE = new PageRequest();
    static final Probe PROBE = new Probe();
    private final Verifier verifier;
    private final Predicate<Request> trusted;
    public VerificationInterceptor(Verifier verifier) {
        this(verifier, request -> VerificationPolicy.trustedUrl(request.url().toString()));
    }
    VerificationInterceptor(Verifier verifier, Predicate<Request> trusted) {
        this.verifier = verifier;
        this.trusted = trusted;
    }
    @Override public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Response response = chain.proceed(original);
        if (original.tag(PageRequest.class) == null || original.tag(Probe.class) != null
                || !original.method().equals("GET") || !trusted.test(original)
                || !trusted.test(response.request()) || !isChallenge(response)) return response;
        response.close();
        ForumDiagnostics.failure("verification", "required");
        if (!verifier.verify(original)) {
            ForumDiagnostics.failure("verification", "cancelled_timeout_or_cooldown");
            throw new IOException("论坛访问验证未完成，请稍后重试（verification/incomplete）");
        }
        // Exactly one replay of the original request, including URL, UA and Referer. Never replay POST.
        Response replay = chain.proceed(original);
        if (isChallenge(replay)) {
            replay.close();
            ForumDiagnostics.failure("verification", "replay_still_challenged");
            throw new IOException("论坛仍要求访问验证，请稍后重试（verification/replay_still_challenged）");
        }
        return replay;
    }
    static boolean isChallenge(Response response) throws IOException {
        int code = response.code();
        if (code != 200 && code != 403 && code != 429 && code != 503) return false;
        return VerificationPolicy.challenge(response.peekBody(262144).string(), response.header("Content-Type"));
    }
}
