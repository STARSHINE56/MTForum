package com.solosu.mtforum.network;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Opt-in anonymous live probe, separate from deterministic unit tests. No credentials or raw HTML saved. */
public final class ForumNetworkProbe {
    public static void main(String[] args) throws Exception {
        StringBuffer report = new StringBuffer();
        ForumDiagnostics.setSink(line -> { report.append(line).append('\n'); System.out.println(line); });
        OkHttpClient client = ForumNetwork.clientBuilder().cookieJar(CookieJar.NO_COOKIES).build();
        String[] urls = {ForumParser.getHomeUrl(1), ForumParser.getThreadListUrl("2", 1), ForumParser.getThreadDetailUrl("172677")};
        String[] stages = {"home_list", "forum_list", "thread_detail"};
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 0; i < urls.length; i++) {
            final int index = i;
            pool.submit(() -> {
                Request request = ForumNetwork.pageRequest(urls[index], ForumNetwork.MOBILE_UA, null);
                try (Response response = client.newCall(request).execute()) {
                    String html = response.body() == null ? "" : response.body().string();
                    if (index == 0) ForumParser.parseThreadList(html);
                    else if (index == 1) ForumParser.parseForumThreadList(html);
                    else ForumParser.parseThreadDetail(html);
                } catch (Exception error) {
                    // Status / guard failures were already classified. Never print messages or stack traces.
                    ForumDiagnostics.failure(stages[index], "probe_failed");
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(170, TimeUnit.SECONDS)) pool.shutdownNow();
        Path output = Path.of(args.length == 0 ? "build/reports/forum-network-probe.txt" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println("Live probe is evidence only; inaccessible pages are not a passing website verification.");
    }
}
