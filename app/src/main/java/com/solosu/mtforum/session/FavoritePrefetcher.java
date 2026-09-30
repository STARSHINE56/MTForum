package com.solosu.mtforum.session;

import android.content.Context;
import android.text.TextUtils;

import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 列表页收藏数预取器。
 * 列表页 DOM 没有收藏数（forumdisplay 只有赞/评论/浏览），收藏数只存在于详情页 HTML
 * 的 #comiis_favorite_a span.comiis_favorite_a_num 中。
 * 这里在列表加载完成后，用小线程池静默逐帖抓详情页，把收藏数写入 FavoritesCache，
 * 然后回调主线程刷新对应位置的卡片，让第四格从"—"变成真实数字。
 */
public class FavoritePrefetcher {

    /** 只补缺口：有缓存的跳过，避免每次进列表都全量重抓 */
    public interface Callback {
        /** 在主线程回调：tid -> 收藏数 */
        void onFavoriteFetched(String tid, int count);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final java.util.Set<String> IN_FLIGHT = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * 为一批帖子预取收藏数（跳过已有缓存的）。
     *
     * @param context  任意 Context（取 application 级使用）
     * @param threads  帖子列表（Thread 或带 tid 的模型，用反射取 getTid）
     * @param callback 每抓到一个回调一次（主线程）；可为 null
     */
    public static void prefetch(final Context context, final List<?> threads, final Callback callback) {
        if (threads == null || threads.isEmpty()) return;
        final Context app = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        for (final Object t : threads) {
            if (t == null) continue;
            final String tid;
            try {
                java.lang.reflect.Method m = t.getClass().getMethod("getTid");
                Object v = m.invoke(t);
                tid = v != null ? v.toString() : null;
            } catch (Exception e) {
                return;
            }
            if (TextUtils.isEmpty(tid)) continue;
            // 已有缓存的不重抓
            if (FavoritesCache.get(app, tid) != null) continue;
            if (!IN_FLIGHT.add(tid)) continue; // 去重：同 tid 不并发抓
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    Integer result = null;
                    try {
                        String html = HttpClient.getInstance().get(
                                ForumParser.getThreadDetailUrl(tid));
                        if (html != null) {
                            result = extractFavoriteCount(html);
                        }
                    } catch (Exception ignore) {
                    } finally {
                        IN_FLIGHT.remove(tid);
                    }
                    if (result != null && result >= 0) {
                        FavoritesCache.put(app, tid, result);
                        if (callback != null) {
                            notifyUi(callback, tid, result);
                        }
                    }
                }
            });
        }
    }

    /** 主线程回调 */
    private static void notifyUi(final Callback cb, final String tid, final int count) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onFavoriteFetched(tid, count);
            }
        });
    }

    /** 从详情页 HTML 提取收藏数（与 ForumParser 同选择器） */
    private static Integer extractFavoriteCount(String html) {
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            org.jsoup.nodes.Element num = doc.selectFirst("#comiis_favorite_a span.comiis_favorite_a_num");
            if (num == null) {
                num = doc.selectFirst("span.comiis_favorite_a_num");
            }
            if (num != null) {
                try {
                    return Integer.parseInt(num.text().replaceAll("[^0-9]", "").trim());
                } catch (NumberFormatException ignore) {
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
