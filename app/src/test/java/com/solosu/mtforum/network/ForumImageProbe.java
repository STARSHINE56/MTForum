package com.solosu.mtforum.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.*;
import org.jsoup.Jsoup;

/** Anonymous evidence only. Does not solve verification or borrow a user's phone cookies. */
public final class ForumImageProbe {
    public static void main(String[] args) throws Exception {
        String page = ForumParser.getThreadDetailUrl("173805");
        StringBuilder report = new StringBuilder("Anonymous real thread/image probe. No personal cookies.\n");
        OkHttpClient client = ForumNetwork.clientBuilder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).cookieJar(CookieJar.NO_COOKIES).build();
        String html = null;
        try (Response response = client.newCall(ForumNetwork.pageRequest(page, ForumNetwork.MOBILE_UA, null)).execute()) {
            html = response.body() == null ? "" : response.body().string();
            report.append("thread status=").append(response.code()).append(" contentType=").append(ForumDiagnostics.safeContentType(response.header("Content-Type")))
                    .append(" final=").append(ForumDiagnostics.safeUrl(response.request().url().toString())).append(" chars=").append(html.length()).append('\n');
            if (VerificationPolicy.challenge(html, response.header("Content-Type"))) {
                report.append("thread verification_page; actual body HTML unavailable; body image URLs and their HTTP/decode results unverified. Android device acceptance required.\n");
                html = null;
            }
        } catch (Exception error) {
            report.append("thread transport=").append(ForumDiagnostics.transportReason(error)).append("; body image requests unverified.\n");
        }
        if (html != null) {
            ForumDiagnostics.setSink(line -> report.append(line).append('\n'));
            ForumDiagnostics.html("real_173805", html);
            try {
                List<String> images = ForumParser.parseThreadDetail(html).getImageUrls();
                report.append("body_images=").append(images.size()).append('\n');
                OkHttpClient.Builder builder = client.newBuilder().followRedirects(false);
                builder.interceptors().clear();
                OkHttpClient binary = builder.addInterceptor(new ForumImageInterceptor(request -> false)).build();
                for (int i=0; i<Math.min(3,images.size());i++) {
                    try(Response response=binary.newCall(new Request.Builder().url(images.get(i)).header("User-Agent",ForumNetwork.MOBILE_UA).header("Referer",page).build()).execute()) {
                        byte[] sample=response.peekBody(32).bytes();
                        report.append("image index=").append(i).append(" status=").append(response.code()).append(" contentType=").append(ForumDiagnostics.safeContentType(response.header("Content-Type"))).append(" size=").append(response.body().contentLength()).append(" signature=").append(signature(sample)).append('\n');
                    } catch(Exception error) {report.append("image index=").append(i).append(" fetch_failed; see safe phase metadata; Glide decoding unverified.\n");}
                }
            } catch (ForumPageGuard.PageFailure error) {
                report.append("thread page_type=").append(error.reason).append("; actual image extraction unverified.\n");
            }
        }
        File destination=new File(args[0]);File parent=destination.getParentFile();if(parent!=null)parent.mkdirs();
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(destination)){stream.write(report.toString().getBytes(StandardCharsets.UTF_8));}
        System.out.print(report);
    }
    private static String signature(byte[] bytes) {
        if(bytes.length>=4 && bytes[0]==(byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G')return "png";
        if(bytes.length>=3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff)return "jpeg";
        if(bytes.length>=3 && bytes[0]=='G' && bytes[1]=='I' && bytes[2]=='F')return "gif";
        if(bytes.length>=12 && bytes[0]=='R' && bytes[1]=='I' && bytes[8]=='W' && bytes[9]=='E')return "webp";
        return "other";
    }
}
