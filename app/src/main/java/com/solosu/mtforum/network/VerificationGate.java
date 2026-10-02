package com.solosu.mtforum.network;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Request;

/** Runs only the site's own scripts. No captcha solving, JS bridge or fabricated clearance tokens. */
public final class VerificationGate implements Application.ActivityLifecycleCallbacks {
    private static final VerificationGate INSTANCE = new VerificationGate();
    public static VerificationGate getInstance() { return INSTANCE; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService probes = Executors.newSingleThreadExecutor();
    private final VerificationCoordinator coordinator = new VerificationCoordinator();
    private WeakReference<Activity> foreground = new WeakReference<>(null);
    private Session session;
    private boolean installed;
    private VerificationGate() {}

    public void install(Application application) {
        if (installed) return;
        installed = true;
        application.registerActivityLifecycleCallbacks(this);
    }

    public void cancelForSessionChange() {
        main.post(() -> { if (session != null) session.finish(false); });
    }

    public boolean verify(Request request) {
        if (!installed || Looper.myLooper() == Looper.getMainLooper()
                || !VerificationPolicy.trustedUrl(request.url().toString())) return false;
        return coordinator.verify(request.url().host() + "|" + request.header("User-Agent"), result -> main.post(() -> {
            if (result.isDone()) return;
            Activity activity = foreground.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                result.complete(false);
                return;
            }
            try {
                session = new Session(activity, request, result);
                session.start();
            } catch (RuntimeException error) {
                ForumDiagnostics.failure("verification_webview", "initialization_failed");
                if (session != null) session.finish(false); else result.complete(false);
            }
        }));
    }

    private final class Session {
        final Activity activity;
        final Request original;
        final CompletableFuture<Boolean> result;
        final Dialog dialog;
        final WebView webView;
        final Handler timer = new Handler(Looper.getMainLooper());
        boolean finished;
        boolean probing;
        boolean visible;
        long lastProbeAt = -1500;
        long startedAt;
        final Runnable poll = new Runnable() {
            @Override public void run() {
                if (finished) return;
                probe();
                inspectManualRequirement();
                timer.postDelayed(this, 5000);
            }
        };
        Session(Activity activity, Request original, CompletableFuture<Boolean> result) {
            this.activity = activity;
            this.original = original;
            this.result = result;
            dialog = new Dialog(activity);
            dialog.setTitle("论坛访问验证");
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(ignored -> finish(false));
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setBackgroundColor(Color.WHITE);
            TextView hint = new TextView(activity);
            hint.setText("自动验证尚未完成。如页面要求操作，请完成验证；通过后会自动继续加载。");
            hint.setTextColor(Color.BLACK);
            hint.setPadding(24, 24, 24, 24);
            layout.addView(hint);
            webView = new WebView(activity);
            layout.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            Button cancel = new Button(activity);
            cancel.setText("取消验证");
            cancel.setOnClickListener(view -> finish(false));
            layout.addView(cancel);
            dialog.setContentView(layout);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(original.header("User-Agent") == null ? HttpClient.USER_AGENT : original.header("User-Agent"));
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
            webView.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    // Embedded challenge resources may be third party; top-level navigation stays on the forum.
                    if (!request.isForMainFrame()) return false;
                    boolean rejected = !VerificationPolicy.trustedUrl(request.getUrl().toString());
                    if (rejected) finish(false);
                    return rejected;
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (!finished && VerificationPolicy.trustedUrl(url)) {
                        probe();
                        inspectManualRequirement();
                    }
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel();
                    finish(false);
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) finish(false);
                }
            });
        }
        void start() {
            if (result.isDone()) { finish(false); return; }
            // Attached and rendered so normal browser challenges can run, initially transparent/non-interactive.
            Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.alpha = 0f;
                attributes.dimAmount = 0f;
                window.setAttributes(attributes);
            }
            dialog.show();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            result.whenComplete((ok, error) -> main.post(() -> finish(Boolean.TRUE.equals(ok))));
            timer.postDelayed(() -> finish(false), 90000);
            startedAt = SystemClock.elapsedRealtime();
            // Slow script-only challenges remain silent. Only visible interactive verification
            // controls may expose the page after the automatic grace period.
            timer.postDelayed(this::inspectManualRequirement, 15000);
            HttpClient.getInstance().prepareVerificationCookies(original.url().toString(), () -> {
                if (finished || result.isDone()) return;
                Map<String, String> headers = new HashMap<>();
                for (String name : new String[]{"Accept", "Accept-Language", "Referer"}) {
                    String value = original.header(name);
                    if (value != null) headers.put(name, value);
                }
                webView.loadUrl(original.url().toString(), headers);
                timer.postDelayed(poll, 5000);
            });
        }
        void inspectManualRequirement() {
            if (finished || visible || SystemClock.elapsedRealtime() - startedAt < 15000) return;
            // Read-only DOM inspection. Never invoke captcha callbacks or write verification tokens.
            webView.evaluateJavascript("(function(){var controls=document.querySelectorAll("
                    + "'.g-recaptcha,.h-captcha,.cf-turnstile,[id*=captcha],[class*=captcha],[class*=slider],"
                    + "input[type=checkbox],input[autocomplete=one-time-code],iframe[src*=recaptcha],"
                    + "iframe[src*=hcaptcha],iframe[src*=turnstile]');"
                    + "for(var i=0;i<controls.length;i++){var e=controls[i],r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                    + "if(r.width>20&&r.height>20&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0')return true;}"
                    + "return false;})()", value -> { if (!finished && "true".equals(value)) showManual(); });
        }
        void showManual() {
            if (finished || visible) return;
            if (activity.isFinishing() || activity.isDestroyed() || foreground.get() != activity) {
                finish(false);
                return;
            }
            visible = true;
            Window window = dialog.getWindow();
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.alpha = 1f;
                attributes.dimAmount = 0.3f;
                window.setAttributes(attributes);
            }
        }
        void probe() {
            if (finished || probing || SystemClock.elapsedRealtime() - lastProbeAt < 1500) return;
            lastProbeAt = SystemClock.elapsedRealtime();
            probing = true;
            CookieManager.getInstance().flush();
            probes.execute(() -> {
                boolean ok = HttpClient.getInstance().probeVerification(original);
                main.post(() -> {
                    probing = false;
                    if (!finished && ok) finish(true);
                });
            });
        }
        void finish(boolean ok) {
            if (finished) return;
            finished = true;
            timer.removeCallbacksAndMessages(null);
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            dialog.dismiss();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            if (session == this) session = null;
            result.complete(ok);
            ForumDiagnostics.failure("verification", ok ? "passed" : "not_completed");
        }
    }
    @Override public void onActivityResumed(Activity activity) { foreground = new WeakReference<>(activity); }
    @Override public void onActivityPaused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
        if (session != null && session.activity == activity) session.finish(false);
    }
    @Override public void onActivityDestroyed(Activity activity) {
        if (session != null && session.activity == activity) session.finish(false);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
