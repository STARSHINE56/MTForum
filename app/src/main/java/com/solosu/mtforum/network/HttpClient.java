package com.solosu.mtforum.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.FormBody;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络请求管理器
 * 基于 OkHttp 封装，支持会话保持（Cookie 持久化）
 */
public class HttpClient {
    public static final String BASE_URL = ForumPageGuard.BASE_URL;
    public static final String MOBILE_SUFFIX = "&mobile=2";
    public static final String USER_AGENT = ForumNetwork.MOBILE_UA;
    public static final String DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static volatile HttpClient instance;
    private OkHttpClient client;
    private OkHttpClient imageClient;
    private volatile String imageSession = java.util.UUID.randomUUID().toString();
    private Map<String, List<Cookie>> cookieStore;
    private static final String PREF_NAME = "sqapp_cookies";
    private static final String KEY_COOKIES = "cookies_json";
    private static boolean initialized = false;
    /** 保存 Application 上下文，用于在每次请求后自动持久化 Cookie */
    private Context appContext;

    /** 飞行中请求去重：相同 URL 的请求未完成时复用同一结果，避免重复发送 */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingGets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingPosts = new ConcurrentHashMap<>();

    private HttpClient() {
        cookieStore = new HashMap<>();
        ForumDiagnostics.setSink(line -> android.util.Log.i("MTForumDiag", line));
        client = ForumNetwork.clientBuilder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        synchronized (cookieStore) {
                        String host = url.host();
                        List<Cookie> existing = cookieStore.get(host);
                        if (existing == null) {
                            existing = new ArrayList<>();
                            cookieStore.put(host, existing);
                        }
                        for (Cookie cookie : cookies) {
                            if (appContext != null && VerificationPolicy.trustedUrl(url.toString())) {
                                CookieManager.getInstance().setCookie(url.toString(), cookie.toString());
                            }
                            boolean found = false;
                            for (int i = 0; i < existing.size(); i++) {
                                if (existing.get(i).name().equals(cookie.name())
                                        && existing.get(i).domain().equals(cookie.domain())
                                        && existing.get(i).path().equals(cookie.path())) {
                                    existing.set(i, cookie);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                existing.add(cookie);
                            }
                        }
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        if (appContext != null && VerificationPolicy.trustedUrl(url.toString())) {
                            return VerificationCookies.forRequest(CookieManager.getInstance().getCookie(url.toString()), url);
                        }
                        List<Cookie> matching = new ArrayList<>();
                        synchronized (cookieStore) {
                            for (List<Cookie> stored : cookieStore.values()) {
                                for (Cookie cookie : stored) {
                                    if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(url)) matching.add(cookie);
                                }
                            }
                        }
                        return matching;
                    }
                })
                .addInterceptor(new VerificationInterceptor(request -> VerificationGate.getInstance().verify(request)))
                .build();
        OkHttpClient.Builder images = client.newBuilder().followRedirects(false).followSslRedirects(false);
        images.interceptors().clear();
        imageClient = images.addInterceptor(new ForumImageInterceptor(request -> VerificationGate.getInstance().verify(request))).build();
    }

    public String imageSessionKey() { return imageSession; }
    public okhttp3.Call newImageCall(Request request) { return imageClient.newCall(request); }

    public static HttpClient getInstance() {
        if (instance == null) {
            synchronized (HttpClient.class) {
                if (instance == null) {
                    instance = new HttpClient();
                }
            }
        }
        return instance;
    }

    private static String readResponseText(Response response) throws IOException {
        try { return response.body() != null ? response.body().string() : ""; }
        catch (IOException error) {
            ForumDiagnostics.failure("body_read", ForumDiagnostics.transportReason(error));
            throw error;
        }
    }

    /**
     * GET 请求
     */
    public String get(String url) throws Exception {
        // 去重：相同 URL 正在请求则等待已有结果，避免重复发送
        CompletableFuture<String> existing = pendingGets.get(url);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingGets.putIfAbsent(url, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            Request request = ForumNetwork.pageRequest(url, USER_AGENT, null);
            try (Response response = client.newCall(request).execute()) {
                String body = readResponseText(response);
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingGets.remove(url, future);
        }
    }

    /**
     * GET 请求返回 byte[]（用于下载图片等二进制内容）
     */
    /**
     * GET 请求(带 Referer,用于需要来源校验的 Comiis 插件/表单接口)。
     */
    public String getWithReferer(String url, String referer) throws Exception {
        Request request = ForumNetwork.pageRequest(url, DESKTOP_USER_AGENT, referer);
        try (Response response = client.newCall(request).execute()) {
            String body = readResponseText(response);
            if (appContext != null) {
                commitCookieStore(appContext);
            }
            return body;
        }
    }

    public String getCookieStringForUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return "";
        if (appContext != null && VerificationPolicy.trustedUrl(url)) {
            String nativeCookies = CookieManager.getInstance().getCookie(url);
            return nativeCookies == null ? "" : nativeCookies;
        }
        StringBuilder value = new StringBuilder();
        synchronized (cookieStore) {
            for (List<Cookie> cookies : cookieStore.values()) {
                for (Cookie cookie : cookies) {
                    if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(parsed)) {
                        if (value.length() > 0) value.append("; ");
                        value.append(cookie.name()).append("=").append(cookie.value());
                    }
                }
            }
        }
        return value.toString();
    }

    public byte[] getBytes(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", BASE_URL)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    /**
     * GET 请求（强制使用桌面端 UA，用于获取桌面版页面中的 hash 等仅桌面版存在的字段）
     */
    public String getDesktop(String url) throws Exception {
        // 去重：相同 URL 正在请求则等待已有结果，避免重复发送
        CompletableFuture<String> existing = pendingGets.get("desktop:" + url);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingGets.putIfAbsent("desktop:" + url, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            Request request = ForumNetwork.pageRequest(url, DESKTOP_USER_AGENT, null);
            try (Response response = client.newCall(request).execute()) {
                String body = readResponseText(response);
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingGets.remove("desktop:" + url, future);
        }
    }

    /**
     * POST 请求（表单提交）
     */
    public String post(String url, Map<String, String> params) throws Exception {
        // POST 去重：URL + 参数的摘要作为 key
        String key = url + (params != null ? params.toString() : "");
        CompletableFuture<String> existing = pendingPosts.get(key);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingPosts.putIfAbsent(key, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            FormBody.Builder formBuilder = new FormBody.Builder();
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                }
            }
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(formBuilder.build())
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = readResponseText(response);
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingPosts.remove(key, future);
        }
    }

    /**
     * POST 请求（表单提交），带 Referer。
     * Discuz 的回复/发帖接口会校验来源页，缺失 Referer 时可能被判为非法操作。
     */
    public String postWithReferer(String url, Map<String, String> params, String referer)
            throws Exception {
        String key = url + "@ref@" + (params != null ? params.toString() : "");
        CompletableFuture<String> existing = pendingPosts.get(key);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingPosts.putIfAbsent(key, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            FormBody.Builder formBuilder = new FormBody.Builder();
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                }
            }
            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded");
            if (!android.text.TextUtils.isEmpty(referer)) rb.header("Referer", referer);
            Request request = rb.post(formBuilder.build()).build();
            try (Response response = client.newCall(request).execute()) {
                String body = readResponseText(response);
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingPosts.remove(key, future);
        }
    }

    /**
     * 上传文件（multipart/form-data）
     * 用于 Discuz! 附件上传接口 forum.php?mod=ajax&action=upload&mobile=2
     */
    public String uploadFile(String url, File file, String fieldName, Map<String, String> extraFields) throws IOException {
        return uploadFileWithUserAgent(url, file, fieldName, extraFields, DESKTOP_USER_AGENT);
    }

    /**
     * multipart 上传并显式指定 UA/Cookie 头。Discuz Comiis 的 swfupload
     * 令牌由桌面版页面生成，上传请求也使用桌面 UA 更稳定。
     */
    public String uploadFileWithUserAgent(String url, File file, String fieldName,
                                          Map<String, String> extraFields, String userAgent) throws IOException {
        return uploadFileWithUserAgent(url, file, fieldName, extraFields, userAgent,
                guessContentType(file));
    }

    /**
     * multipart 上传，允许调用方保留系统相册提供的真实 MIME 类型。
     * 部分 Discuz/Comiis 站点会同时校验文件名扩展名和 Content-Type；把 PNG、WebP、HEIC
     * 一律伪装为 jpg/octet-stream 会导致网页端能传、原生端被拒绝。
     */
    public String uploadFileWithUserAgent(String url, File file, String fieldName,
                                          Map<String, String> extraFields, String userAgent,
                                          String contentType) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) {
            throw new IOException("上传文件不存在或为空");
        }

        // 登录可能发生在 WebView。仅当原生会话缺失时才拉取，避免空/旧 WebView Cookie
        // 覆盖仍然有效的 OkHttp 登录态；随后仍显式把当前 Cookie 写入上传请求。
        if (!isLoggedIn()) syncFromCookieManager();
        String cookieHeader = getCookieHeader();

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(fieldName, file.getName(),
                        okhttp3.RequestBody.create(MediaType.parse(
                                contentType == null || contentType.trim().isEmpty()
                                        ? "application/octet-stream" : contentType), file));

        if (extraFields != null) {
            for (Map.Entry<String, String> entry : extraFields.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.addFormDataPart(entry.getKey(), entry.getValue());
                }
            }
        }

        okhttp3.RequestBody requestBody = builder.build();
        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent == null || userAgent.isEmpty()
                        ? DESKTOP_USER_AGENT : userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", BASE_URL)
                .post(requestBody);
        // 手动设置 Cookie 时 OkHttp 不会重复由 CookieJar 注入同名 Header。
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            requestBuilder.header("Cookie", cookieHeader);
        }

        try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
            String body = readResponseText(response);
            if (appContext != null) {
                commitCookieStore(appContext);
            }
            if (!response.isSuccessful()) {
                throw new IOException("上传请求失败（HTTP " + response.code() + "）");
            }
            return body;
        }
    }

    private static String guessContentType(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".heic") || name.endsWith(".heif")) return "image/heic";
        return "application/octet-stream";
    }

    /**
     * ★ 使用 HttpClient 的 OkHttpClient（含 Cookie）执行自定义 Request
     * 用于头像上传等需要保持会话的外部请求
     * 返回 okhttp3.Response（调用方记得关闭 response.body()）
     */
    public okhttp3.Response executeDirect(okhttp3.Request request) throws Exception {
        okhttp3.Response response = client.newCall(request).execute();
        // 请求完成后自动持久化 Cookie
        if (appContext != null) {
            commitCookieStore(appContext);
        }
        return response;
    }

    /**
     * 获取当前 cookie 值
     */
    public String getCookieValue(String host, String name) {
        HttpUrl origin = HttpUrl.parse("https://" + host + "/");
        if (origin == null) return null;
        for (Cookie cookie : client.cookieJar().loadForRequest(origin)) {
            if (cookie.name().equals(name)) return cookie.value();
        }
        return null;
    }

/**
     * 获取指定 URL 域名的所有 Cookie 字符串（用于同步到 WebView）
     * 格式: "name1=value1; name2=value2"
     */
    public String getCookieString() {
        return getCookieStringForUrl(BASE_URL);
    }

    /**
     * 判断是否已登录（检查是否有 Discuz 的 auth cookie）
     * 遍历所有 cookie，查找名称以 _auth 结尾的 cookie
     * 兼容不同论坛实例的不同 cookiepre 前缀
     */
    public boolean isLoggedIn() {
        for (Cookie cookie : client.cookieJar().loadForRequest(HttpUrl.get(BASE_URL))) {
            if (cookie.name().endsWith("_auth") && !cookie.value().isEmpty()) return true;
        }
        return false;
    }
    /** 返回当前论坛域名的 Cookie Header，供需要显式携带会话的 multipart 请求使用。 */
    public synchronized String getCookieHeader() {
        return getCookieString();
    }

    /**
     * 从 WebView CookieManager 同步 Cookie。不同 Android/Chromium 版本对
     * BASE_URL、host URL 和末尾斜杠的处理不同，因此依次读取多个等价地址。
     */
    public synchronized void syncFromCookieManager() {
        CookieManager manager = CookieManager.getInstance();
        manager.flush();
        HttpUrl url = HttpUrl.get(BASE_URL);
        List<Cookie> nativeCookies = new ArrayList<>();
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.GET_COOKIE_INFO)) {
            for (String info : androidx.webkit.CookieManagerCompat.getCookieInfo(manager, BASE_URL)) {
                Cookie cookie = Cookie.parse(url, info);
                if (cookie != null) nativeCookies.add(cookie);
            }
        } else {
            // Older WebViews expose only a request header. Keep a session-only account snapshot;
            // actual requests still read the native jar, which retains expiry/path/HttpOnly.
            nativeCookies = VerificationCookies.forRequest(manager.getCookie(BASE_URL), url);
        }
        synchronized (cookieStore) { cookieStore.put(url.host(), nativeCookies); }
        if (appContext != null) commitCookieStore(appContext);
    }


    /** Probe uses the identical request and shared CookieJar, but never triggers another gate. */
    boolean probeVerification(Request original) {
        Request probe = original.newBuilder().tag(VerificationInterceptor.Probe.class, VerificationInterceptor.PROBE).build();
        OkHttpClient probeClient = client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build();
        try (Response response = probeClient.newCall(probe).execute()) {
            if (response.code() != 200 || !VerificationPolicy.trustedUrl(response.request().url().toString())) return false;
            String body = readResponseText(response);
            return VerificationPolicy.forumContent(body)
                    && !VerificationPolicy.challenge(body, response.header("Content-Type"));
        } catch (IOException error) {
            ForumDiagnostics.failure("verification_probe", ForumDiagnostics.transportReason(error));
            return false;
        }
    }

    /** Seed WebView before loading; retain real Set-Cookie attributes and await the final native write. */
    void prepareVerificationCookies(String url, Runnable ready) {
        HttpUrl target = HttpUrl.get(url);
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        // Existing native cookies already include server responses and prior successful challenges.
        // Only migrate the legacy jar when the native jar is absent, never overwrite fresher cookies.
        if (manager.getCookie(url) != null) { ready.run(); return; }
        List<Cookie> cookies = new ArrayList<>();
        synchronized (cookieStore) {
            for (List<Cookie> stored : cookieStore.values()) for (Cookie cookie : stored) {
                if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(target)) cookies.add(cookie);
            }
        }
        seedVerificationCookie(manager, url, cookies, 0, ready);
    }

    private void seedVerificationCookie(CookieManager manager, String url, List<Cookie> cookies, int index, Runnable ready) {
        if (index == cookies.size()) { manager.flush(); ready.run(); return; }
        manager.setCookie(url, cookies.get(index).toString(), accepted ->
                seedVerificationCookie(manager, url, cookies, index + 1, ready));
    }


    /**
     * 将 HttpClient CookieJar 中的 Cookie 同步到 Android WebView CookieManager。
     * 用于登录由原生 OkHttp 完成、随后需要在 WebView 或混合页面中继续使用登录态的场景。
     */
    public synchronized void syncToCookieManager() {
        CookieManager manager = CookieManager.getInstance();
        if (manager.getCookie(BASE_URL) != null) return;
        synchronized (cookieStore) {
            for (List<Cookie> stored : cookieStore.values()) for (Cookie cookie : stored) {
                HttpUrl origin = new HttpUrl.Builder().scheme("https")
                        .host(cookie.domain()).encodedPath(cookie.path()).build();
                if (cookie.expiresAt() > System.currentTimeMillis()
                        && cookie.matches(HttpUrl.get(BASE_URL)) && VerificationPolicy.trustedUrl(origin.toString())) {
                    manager.setCookie(origin.toString(), cookie.toString());
                }
            }
        }
        manager.flush();
    }

/**
     * 初始化 — 从磁盘恢复持久化的 Cookie
     * 在 Application.onCreate() 或首次使用前调用一次
     * 初始化后自动从 WebView CookieManager 拉取一次 Cookie，确保双向同步
     */
    public synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        this.appContext = context.getApplicationContext();
        restoreCookieStoreInternal(context, false);
        CookieManager.getInstance().setAcceptCookie(true);
        if (CookieManager.getInstance().getCookie(BASE_URL) == null) syncToCookieManager();
        syncFromCookieManager();
    }

    /**
     * 将当前内存中的 Cookie 持久化到 SharedPreferences（JSON 序列化）
     * 在登录成功后调用
     */
    public synchronized void commitCookieStore(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            JSONArray cookiesArray = new JSONArray();

            synchronized (cookieStore) {
            for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
                String host = entry.getKey();
                for (Cookie cookie : entry.getValue()) {
                    JSONObject obj = new JSONObject();
                    obj.put("host", host);
                    obj.put("name", cookie.name());
                    obj.put("value", cookie.value());
                    obj.put("domain", cookie.domain());
                    obj.put("path", cookie.path());
                    obj.put("expiresAt", cookie.expiresAt());
                    obj.put("secure", cookie.secure());
                    obj.put("httpOnly", cookie.httpOnly());
                    obj.put("hostOnly", cookie.hostOnly());
                    obj.put("persistent", cookie.persistent());
                    if (cookie.persistent()) {
                        // 只持久化 persistent cookie（登录态 cookie 通常是 persistent 的）
                        cookiesArray.put(obj);
                    } else {
                        // session cookie 也存一下，避免某些情况丢失
                        cookiesArray.put(obj);
                    }
                }
            }

            }
            prefs.edit().putString(KEY_COOKIES, cookiesArray.toString()).apply();
        } catch (Exception ignored) {
            // 序列化失败不抛出
        }
    }

    /**
     * 从 SharedPreferences 恢复 Cookie 到内存
     * 在应用启动时调用
     */
    public synchronized void restoreCookieStore(Context context) {
        restoreCookieStoreInternal(context, true);
        syncToCookieManager();
    }

    private void restoreCookieStoreInternal(Context context, boolean explicitAccountSwitch) {
        if (explicitAccountSwitch) {
            imageSession = java.util.UUID.randomUUID().toString();
            imageClient.dispatcher().cancelAll();
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_COOKIES, null);
            if (json == null || json.isEmpty()) return;

            JSONArray cookiesArray = new JSONArray(json);
            cookieStore.clear();

            for (int i = 0; i < cookiesArray.length(); i++) {
                JSONObject obj = cookiesArray.getJSONObject(i);
                String host = obj.optString("host", HttpUrl.parse(BASE_URL).host());
                // Header-only WebView snapshots must never become permanent clearance cookies.
                if (!explicitAccountSwitch && !obj.optBoolean("persistent", true)) continue;

                Cookie.Builder builder = new Cookie.Builder()
                        .name(obj.getString("name"))
                        .value(obj.getString("value"))
                        .path(obj.optString("path", "/"));
                String domain = obj.optString("domain", HttpUrl.parse(BASE_URL).host());
                if (obj.optBoolean("hostOnly")) builder.hostOnlyDomain(domain); else builder.domain(domain);
                if (obj.optBoolean("persistent", true)) builder.expiresAt(obj.optLong("expiresAt", Long.MAX_VALUE));

                if (obj.optBoolean("secure")) {
                    builder.secure();
                }
                if (obj.optBoolean("httpOnly")) {
                    builder.httpOnly();
                }

                Cookie cookie = builder.build();

                List<Cookie> cookies = cookieStore.get(host);
                if (cookies == null) {
                    cookies = new ArrayList<>();
                    cookieStore.put(host, cookies);
                }
                cookies.add(cookie);
            }
        } catch (Exception ignored) {
            // 反序列化失败时使用空 cookieStore（ignored）
        }
    }

    /**
     * 清除所有 Cookie（内存 + 磁盘）
     */
    /**
     * 从原始 Cookie 字符串设置 Cookie(用于 Cookie 登录)。
     * 格式: "key1=value1; key2=value2; ..."
     * 设置后可通过 isLoggedIn() 检查 _auth cookie 是否有效。
     */
    public synchronized void applyCookiesFromString(String cookieString) {
        if (cookieString == null || cookieString.trim().isEmpty()) return;
        String host = HttpUrl.parse(BASE_URL).host();
        List<Cookie> existing = cookieStore.get(host);
        if (existing == null) {
            existing = new ArrayList<>();
            cookieStore.put(host, existing);
        }
        String[] pairs = cookieString.split(";\\s*");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            CookieManager.getInstance().setCookie(BASE_URL, name + "=" + value + "; Path=/; Secure");
            Cookie.Builder b = new Cookie.Builder().name(name).value(value)
                    .domain(host).path("/").expiresAt(Long.MAX_VALUE);
            boolean replaced = false;
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).name().equals(name)) {
                    existing.set(i, b.build());
                    replaced = true;
                    break;
                }
            }
            if (!replaced) existing.add(b.build());
        }
    }

    public void clearCookies() {
        imageSession = java.util.UUID.randomUUID().toString();
        imageClient.dispatcher().cancelAll();
        VerificationGate.getInstance().cancelForSessionChange();
        CookieManager manager = CookieManager.getInstance();
        // Delete only cookies visible to this forum, synchronously before account replacement.
        java.util.Set<String> urls = new java.util.HashSet<>();
        urls.add(BASE_URL);
        synchronized (cookieStore) {
            for (List<Cookie> stored : cookieStore.values()) for (Cookie cookie : stored) {
                if (cookie.domain().equals("bbs.binmt.cc")) urls.add(BASE_URL.substring(0, BASE_URL.length() - 1) + cookie.path());
            }
        }
        for (String url : urls) {
            String header = manager.getCookie(url);
            HttpUrl parsed = HttpUrl.get(url);
            for (Cookie cookie : VerificationCookies.forRequest(header, parsed)) {
                for (String domain : new String[]{"", "; Domain=bbs.binmt.cc", "; Domain=.bbs.binmt.cc"}) {
                    manager.setCookie(url, cookie.name() + "=; Path=" + parsed.encodedPath()
                            + domain + "; Max-Age=0; Secure");
                    manager.setCookie(BASE_URL, cookie.name() + "=; Path=/" + domain + "; Max-Age=0; Secure");
                }
            }
        }
        manager.flush();
        synchronized (cookieStore) { cookieStore.clear(); }
    }
    /**
     * 清除所有 Cookie（内存 + 磁盘 + WebView）
     * 在登出时调用
     */
    public void clearCookies(Context context) {
        clearCookies();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_COOKIES).apply();
        } catch (Exception ignored) {
        }

    }
}
