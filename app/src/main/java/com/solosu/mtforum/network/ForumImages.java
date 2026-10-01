package com.solosu.mtforum.network;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

public final class ForumImages {
    private ForumImages() {}
    public static GlideUrl request(String source, String pageUrl) {
        String url = ForumPageGuard.imageUrl(source, pageUrl);
        if (url == null) return null;
        LazyHeaders.Builder headers = new LazyHeaders.Builder()
                .addHeader("User-Agent", HttpClient.USER_AGENT)
                .addHeader("Referer", pageUrl);
        String cookies = HttpClient.getInstance().getCookieStringForUrl(url);
        if (!cookies.isEmpty()) headers.addHeader("Cookie", cookies);
        return new GlideUrl(url, headers.build());
    }
}
