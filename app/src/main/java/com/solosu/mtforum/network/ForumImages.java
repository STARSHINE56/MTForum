package com.solosu.mtforum.network;

import android.content.Context;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Only this model uses forum transport. Local files and other Glide models are unchanged. */
public final class ForumImages {
    private ForumImages() {}
    public static void install(Context context) {
        Glide.get(context).getRegistry().append(ImageRequest.class, InputStream.class, new Factory());
    }
    public static ImageRequest request(String source, String pageUrl) {
        String url = ForumPageGuard.imageUrl(source, pageUrl);
        if (url == null) { ForumDiagnostics.failure("image_parse", "invalid_url"); return null; }
        String referer = VerificationPolicy.trustedUrl(pageUrl) ? pageUrl : HttpClient.BASE_URL;
        return new ImageRequest(url, referer, HttpClient.getInstance().imageSessionKey());
    }
    public static final class ImageRequest implements Key {
        final String url, referer, session;
        ImageRequest(String url, String referer, String session) { this.url = url; this.referer = referer; this.session = session; }
        Request httpRequest() {
            return new Request.Builder().url(url).header("User-Agent", HttpClient.USER_AGENT)
                    .header("Referer", referer).header("Accept", "image/*,*/*;q=0.8")
                    .tag(ForumImageInterceptor.Context.class, new ForumImageInterceptor.Context(referer, session)).get().build();
        }
        @Override public void updateDiskCacheKey(MessageDigest digest) {
            digest.update((url + "\n" + referer + "\n" + session).getBytes(Key.CHARSET));
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ImageRequest)) return false;
            ImageRequest value = (ImageRequest) other;
            return url.equals(value.url) && referer.equals(value.referer) && session.equals(value.session);
        }
        @Override public int hashCode() { return Objects.hash(url, referer, session); }
        @Override public String toString() { return "ForumImage(" + ForumDiagnostics.safeUrl(url) + ")"; }
    }
    public static RequestListener<Drawable> listener() {
        return new RequestListener<Drawable>() {
            @Override public boolean onLoadFailed(GlideException error, Object model, Target<Drawable> target, boolean first) {
                String reason = "decode_or_resource";
                if (error != null) for (Throwable cause : error.getRootCauses()) {
                    if (cause instanceof ForumImageInterceptor.ImageFailure) { reason = "http_fetch"; break; }
                }
                ForumDiagnostics.failure("image_glide", reason);
                return false;
            }
            @Override public boolean onResourceReady(Drawable value, Object model, Target<Drawable> target, DataSource source, boolean first) { return false; }
        };
    }
    private static final class Factory implements ModelLoaderFactory<ImageRequest, InputStream> {
        @Override public ModelLoader<ImageRequest, InputStream> build(MultiModelLoaderFactory multi) {
            return new ModelLoader<ImageRequest, InputStream>() {
                @Override public LoadData<InputStream> buildLoadData(ImageRequest model, int width, int height, Options options) {
                    return new LoadData<>(model, new Fetcher(model));
                }
                @Override public boolean handles(ImageRequest model) { return true; }
            };
        }
        @Override public void teardown() {}
    }
    private static final class Fetcher implements DataFetcher<InputStream> {
        final ImageRequest model;
        volatile Call call;
        volatile boolean cancelled;
        Response response;
        Fetcher(ImageRequest model) { this.model = model; }
        @Override public void loadData(Priority priority, DataCallback<? super InputStream> callback) {
            try {
                if (cancelled) return;
                call = HttpClient.getInstance().newImageCall(model.httpRequest());
                if (cancelled) { call.cancel(); return; }
                response = call.execute();
                if (cancelled) { cleanup(); return; }
                if (!model.session.equals(HttpClient.getInstance().imageSessionKey())) {
                    cleanup(); callback.onLoadFailed(new ForumImageInterceptor.ImageFailure("session_changed")); return;
                }
                callback.onDataReady(response.body().byteStream());
            } catch (Exception error) {
                // Forward no raw URL, headers or exception messages to Glide's default logs.
                if (!cancelled) callback.onLoadFailed(error instanceof ForumImageInterceptor.ImageFailure
                        ? error : new ForumImageInterceptor.ImageFailure("transport"));
            }
        }
        @Override public void cleanup() { if (response != null) { response.close(); response = null; } }
        @Override public void cancel() { cancelled = true; if (call != null) call.cancel(); }
        @Override public Class<InputStream> getDataClass() { return InputStream.class; }
        @Override public DataSource getDataSource() { return DataSource.REMOTE; }
    }
}
