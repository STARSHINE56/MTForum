package com.solosu.mtforum.network;

import java.util.ArrayList;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.HttpUrl;

/** CookieManager has already filtered the header by URL, expiry, domain and path.
 * These ephemeral cookies are used for that exact request only, never persisted as immortal cookies. */
public final class VerificationCookies {
    private VerificationCookies() {}
    public static List<Cookie> forRequest(String header, HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        if (header == null || !VerificationPolicy.trustedUrl(url.toString())) return result;
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            try {
                Cookie.Builder cookie = new Cookie.Builder().name(pair.substring(0, equals).trim())
                        .value(pair.substring(equals + 1).trim()).hostOnlyDomain(url.host()).path("/").secure();
                result.add(cookie.build());
            } catch (IllegalArgumentException ignored) { /* Skip one malformed cookie, never log its value. */ }
        }
        return result;
    }
}
