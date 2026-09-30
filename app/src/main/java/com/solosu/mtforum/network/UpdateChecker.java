package com.solosu.mtforum.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.solosu.mtforum.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.HttpUrl;

/** 飞际盘分享链接更新检查器。 */
// [UPDATE MODULE disabled] public final class UpdateChecker {

//     public static final String UPDATE_URL = "https://share.feijipan.com/s/et7C4z0u";
//     private static final String API_URL = "https://api.feijipan.com/ws/share/list";
//     private static final String PREF_NAME = "sqapp_update_checker";
//     private static final String KEY_LAST_FILE_UPDATE_TIME = "last_remote_file_update_time";
//     private static final String AES_KEY = "dingHao-disk-app";
//     private static final Pattern VERSION_PATTERN =
//             Pattern.compile("([0-9]+(?:\\.[0-9]+)*)$");

//     private UpdateChecker() {
//     }

//     public interface Callback {
//         void onResult(Result result);
//     }

//     public static final class Result {
//         public final boolean success;
//         public final boolean hasUpdate;
//         public final boolean firstCheck;
//         public final String remoteUpdateTime;
//         public final String remoteFileName;
//         public final String remoteVersion;
//         public final int itemCount;
//         public final String errorMessage;

//         private Result(boolean success, boolean hasUpdate, boolean firstCheck,
//                        String remoteUpdateTime, String remoteFileName, String remoteVersion,
//                        int itemCount, String errorMessage) {
//             this.success = success;
//             this.hasUpdate = hasUpdate;
//             this.firstCheck = firstCheck;
//             this.remoteUpdateTime = remoteUpdateTime;
//             this.remoteFileName = remoteFileName;
//             this.remoteVersion = remoteVersion;
//             this.itemCount = itemCount;
//             this.errorMessage = errorMessage;
//         }

//         static Result success(boolean hasUpdate, boolean firstCheck,
//                               String remoteUpdateTime, String remoteFileName,
//                               String remoteVersion, int itemCount) {
//             return new Result(true, hasUpdate, firstCheck, remoteUpdateTime,
//                     remoteFileName, remoteVersion, itemCount, null);
//         }

//         static Result failure(String message) {
//             return new Result(false, false, false, null, null, null, 0,
//                     message == null || message.trim().isEmpty() ? "检查更新失败" : message);
//         }
//     }

//     /** 在后台线程检查，回调始终运行在主线程。 */
//     public static void check(Context context, Callback callback) {
//         Context appContext = context.getApplicationContext();
//         new Thread(() -> {
//             Result result;
//             try {
//                 result = request(appContext);
//             } catch (Exception e) {
//                 result = Result.failure(e.getMessage());
//             }
//             Result finalResult = result;
//             new Handler(Looper.getMainLooper()).post(() -> {
//                 if (callback != null) callback.onResult(finalResult);
//             });
//         }, "update-check").start();
//     }

//     private static Result request(Context context) throws Exception {
//         JSONObject rootJson = requestJson(context, null);
//         JSONArray rootList = rootJson.optJSONArray("list");
//         if (rootList == null || rootList.length() == 0) {
//             throw new IllegalStateException("更新文件夹为空");
//         }

//         JSONObject rootItem = rootList.optJSONObject(0);
//         if (rootItem == null) {
//             throw new IllegalStateException("更新文件夹信息无效");
//         }

//         String folderId = firstNonEmpty(rootItem.optString("folderId", ""),
//                 rootItem.optString("sortId", ""));
//         if (folderId.isEmpty()) {
//             throw new IllegalStateException("无法获取更新文件夹");
//         }

//         JSONObject fileJson = requestJson(context, folderId);
//         JSONArray fileList = fileJson.optJSONArray("list");
//         if (fileList == null || fileList.length() == 0) {
//             throw new IllegalStateException("更新文件夹内没有文件");
//         }

//         JSONObject latestFile = null;
//         JSONObject latestVersionFile = null;
//         String latestTime = "";
//         String latestVersion = null;
//         for (int i = 0; i < fileList.length(); i++) {
//             JSONObject item = fileList.optJSONObject(i);
//             if (item == null) continue;
//             if (item.optInt("fileType", 1) == 2) continue;

//             String updateTime = firstNonEmpty(item.optString("updTime", ""),
//                     item.optString("addTime", ""));
//             if (latestFile == null || updateTime.compareTo(latestTime) > 0) {
//                 latestFile = item;
//                 latestTime = updateTime;
//             }

//             // 不只看时间最新的一项：若最新文件是无版本号的说明文件，
//             // 仍然要从其他文件中找到真正的 APK 版本文件。
//             String itemName = firstNonEmpty(item.optString("fileName", ""),
//                     item.optString("name", ""));
//             String itemVersion = extractVersion(itemName);
//             if (itemVersion != null
//                     && (latestVersion == null
//                     || compareVersions(itemVersion, latestVersion) > 0
//                     || (compareVersions(itemVersion, latestVersion) == 0
//                     && updateTime.compareTo(firstNonEmpty(
//                     latestVersionFile == null ? "" : latestVersionFile.optString("updTime", ""),
//                     "")) > 0))) {
//                 latestVersion = itemVersion;
//                 latestVersionFile = item;
//             }
//         }

//         if (latestFile == null) {
//             throw new IllegalStateException("更新文件夹内没有可用文件");
//         }

//         // 优先使用带版本号的文件；没有版本号时保留最新文件信息并返回无版本结果。
//         JSONObject selectedFile = latestVersionFile != null ? latestVersionFile : latestFile;
//         String fileName = firstNonEmpty(selectedFile.optString("fileName", ""),
//                 selectedFile.optString("name", ""));
//         String remoteVersion = extractVersion(fileName);
//         String selectedTime = firstNonEmpty(selectedFile.optString("updTime", ""),
//                 selectedFile.optString("addTime", ""));
//         if (!selectedTime.isEmpty()) {
//             latestTime = selectedTime;
//         }
//         if (latestTime.isEmpty()) {
//             latestTime = firstNonEmpty(selectedFile.optString("fileId", ""),
//                     rootItem.optString("updTime", ""));
//         }
//         if (latestTime.isEmpty()) {
//             throw new IllegalStateException("更新文件时间无效");
//         }

//         SharedPreferences preferences = context.getSharedPreferences(
//                 PREF_NAME, Context.MODE_PRIVATE);
//         String previous = preferences.getString(KEY_LAST_FILE_UPDATE_TIME, "");
//         boolean firstCheck = previous.isEmpty();

//         // 版本号是最终判断依据，不能再依赖“时间是否变化”来决定是否提示。
//         // 旧版本曾在弹窗刚显示时就保存时间，导致用户关闭弹窗后永久被判定为最新；
//         // 因此只要远程文件版本高于当前版本，每次检查都必须继续提示，直到用户安装新版本。
//         boolean versionIsNew = remoteVersion != null
//                 && compareVersions(remoteVersion, BuildConfig.VERSION_NAME) > 0;
//         boolean hasUpdate = versionIsNew;

//         // 无待更新版本时才记录时间。待更新文件不能标记为已处理，避免关闭弹窗后不再提醒。
//         if (!hasUpdate) {
//             preferences.edit().putString(KEY_LAST_FILE_UPDATE_TIME, latestTime).apply();
//         }

//         return Result.success(hasUpdate, firstCheck, latestTime, fileName,
//                 remoteVersion, fileList.length());
//     }

//     private static JSONObject requestJson(Context context, String folderId) throws Exception {
//         String uuid = getOrCreateUuid(context);
//         String now = String.valueOf(System.currentTimeMillis());
//         HttpUrl.Builder urlBuilder = HttpUrl.parse(API_URL).newBuilder()
//                 .addQueryParameter("code", "")
//                 .addQueryParameter("shareId", "et7C4z0u")
//                 .addQueryParameter("userId", "")
//                 .addQueryParameter("type", "0")
//                 .addQueryParameter("offset", "1")
//                 .addQueryParameter("limit", "110")
//                 .addQueryParameter("referer", UPDATE_URL)
//                 .addQueryParameter("devType", "3")
//                 .addQueryParameter("devModel", "Android")
//                 .addQueryParameter("uuid", uuid)
//                 .addQueryParameter("extra", "2")
//                 .addQueryParameter("timestamp", encryptHex(now));
//         if (folderId != null && !folderId.isEmpty()) {
//             urlBuilder.addQueryParameter("folderId", folderId);
//         }

//         String body = HttpClient.getInstance().post(urlBuilder.build().toString(),
//                 Collections.emptyMap());
//         JSONObject json = new JSONObject(body);
//         if (json.optInt("code", -1) != 200) {
//             throw new IllegalStateException(json.optString("msg", "更新源暂不可用"));
//         }
//         return json;
//     }

//     /** 从扩展名前的末尾数字提取版本：xxx1.12.apk -> 1.12。 */
//     public static String extractVersion(String fileName) {
//         if (fileName == null) return null;
//         String name = fileName.trim();
//         if (name.isEmpty()) return null;
//         int lastDot = name.lastIndexOf('.');
//         String withoutExtension = lastDot > 0 ? name.substring(0, lastDot).trim() : name;
//         Matcher matcher = VERSION_PATTERN.matcher(withoutExtension);
//         return matcher.find() ? matcher.group(1) : null;
//     }

//     /** 按数字段比较，避免 1.10 被字符串比较误判为低于 1.2。 */
//     public static int compareVersions(String left, String right) {
//         if (left == null || right == null) return 0;
//         String[] leftParts = left.split("\\.");
//         String[] rightParts = right.split("\\.");
//         int count = Math.max(leftParts.length, rightParts.length);
//         for (int i = 0; i < count; i++) {
//             String l = i < leftParts.length ? leftParts[i] : "0";
//             String r = i < rightParts.length ? rightParts[i] : "0";
//             int compare = new BigInteger(normalizeNumber(l))
//                     .compareTo(new BigInteger(normalizeNumber(r)));
//             if (compare != 0) return compare;
//         }
//         return 0;
//     }

//     private static String normalizeNumber(String value) {
//         String normalized = value == null ? "0" : value.replaceFirst("^0+(?!$)", "");
//         return normalized.isEmpty() ? "0" : normalized;
//     }

//     private static String firstNonEmpty(String first, String second) {
//         return first != null && !first.isEmpty() ? first
//                 : (second == null ? "" : second);
//     }

//     private static String getOrCreateUuid(Context context) {
//         SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
//         String uuid = preferences.getString("client_uuid", "");
//         if (uuid == null || uuid.isEmpty()) {
//             uuid = UUID.randomUUID().toString();
//             preferences.edit().putString("client_uuid", uuid).apply();
//         }
//         return uuid;
//     }

//     /** AES-128-ECB + PKCS5Padding，输出大写十六进制。 */
//     private static String encryptHex(String value) throws Exception {
//         Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
//         cipher.init(Cipher.ENCRYPT_MODE,
//                 new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
//         byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
//         StringBuilder result = new StringBuilder(encrypted.length * 2);
//         for (byte b : encrypted) result.append(String.format("%02X", b & 0xFF));
//         return result.toString();
//     }
// }
// [UPDATE MODULE] temporarily disabled (uncomment all lines to re-enable)
