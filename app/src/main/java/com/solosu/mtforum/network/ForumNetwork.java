package com.solosu.mtforum.network;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Shared transport settings for the app and the anonymous diagnostic probe. */
public final class ForumNetwork {
    public static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private ForumNetwork() {}
    public static Request pageRequest(String url, String userAgent, String referer) {
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .tag(VerificationInterceptor.PageRequest.class, VerificationInterceptor.PAGE).get();
        if (referer != null) request.header("Referer", referer);
        return request.build();
    }
    public static OkHttpClient.Builder clientBuilder() {
        return new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true)
                .addInterceptor(chain -> {
                    ForumDiagnostics.start(chain.request().url().toString());
                    Response response;
                    try { response = chain.proceed(chain.request()); }
                    catch (IOException error) { ForumDiagnostics.transportFailure(error); throw error; }
                    ForumDiagnostics.response(response);
                    try {
                        if (response.code() < 200 || response.code() >= 300) {
                            if (ForumDiagnostics.safeContentType(response.header("Content-Type")).matches("text/html|application/xhtml\\+xml")) {
                                try { ForumDiagnostics.html("error_response_sample", response.peekBody(65536).string()); }
                                catch (IOException ignored) { ForumDiagnostics.failure("error_response_sample", "unavailable"); }
                            }
                        }
                        ResponsePolicy.checkStatus(response.code());
                        if (chain.request().url().isHttps() && !response.request().url().isHttps()) {
                            ForumDiagnostics.failure("response", "https_downgrade");
                            throw new IOException("拒绝将 HTTPS 请求重定向到不安全的 HTTP 地址");
                        }
                        return response;
                    } catch (IOException error) { response.close(); throw error; }
                });
    }
}
