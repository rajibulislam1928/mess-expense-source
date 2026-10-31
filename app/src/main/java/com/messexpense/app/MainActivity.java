package com.messexpense.app;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;

public class MainActivity extends android.app.Activity {
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String LEGACY_URL = "file:///android_asset/index.html";
    private static final String MIGRATED_KEY = "file_origin_migrated_v1";
    private static final int MAX_MIGRATION_BYTES = 512 * 1024;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private boolean migrationStarted;
    private FirebaseBridge firebaseBridge;
    private FileBridge fileBridge;
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshUiActiveMarker = new Runnable() {
        @Override public void run() {
            getSharedPreferences(BackgroundSyncWorker.PREFS, MODE_PRIVATE).edit()
                    .putLong(BackgroundSyncWorker.KEY_UI_ACTIVE_AT, System.currentTimeMillis()).apply();
            lifecycleHandler.postDelayed(this, 30_000L);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.confirm())
                        .show();
                return true;
            }
        });

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        if (getPreferences(MODE_PRIVATE).getBoolean(MIGRATED_KEY, false)) {
            loadSecureApp("");
        } else {
            // One-time local-only pass reads the old file-origin localStorage so upgrades keep
            // the user's existing tracker. Network loads remain blocked during this pass.
            settings.setAllowFileAccess(true);
            settings.setBlockNetworkLoads(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (migrationStarted || !LEGACY_URL.equals(url)) return;
                    migrationStarted = true;
                    webView.evaluateJavascript(
                            "(function(){try{return localStorage.getItem('mess-expense-state-v1')||'';}catch(e){return '';}})()",
                            MainActivity.this::finishLegacyMigration);
                }
            });
            webView.loadUrl(LEGACY_URL);
        }
    }

    private void finishLegacyMigration(String encodedResult) {
        String legacyState = decodeJavascriptString(encodedResult);
        byte[] bytes = legacyState.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MIGRATION_BYTES) {
            webView.evaluateJavascript(
                    "if(window.setAuthStatus)setAuthStatus('Your saved tracker is too large for automatic upgrade. Use Export backup before updating this app.', 'error');",
                    null);
            return;
        }
        String fragment = legacyState.isEmpty() ? "" : "#legacy-state=" +
                Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        loadSecureApp(fragment);
    }

    private String decodeJavascriptString(String result) {
        if (result == null || "null".equals(result) || "undefined".equals(result)) return "";
        try {
            JSONArray wrapper = new JSONArray("[" + result + "]");
            return wrapper.isNull(0) ? "" : wrapper.getString(0);
        } catch (Exception ignored) {
            return "";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadSecureApp(String fragment) {
        if (firebaseBridge == null) {
            firebaseBridge = new FirebaseBridge(this, webView);
            webView.addJavascriptInterface(firebaseBridge, "MessFirebase");
            fileBridge = new FileBridge(this, webView);
            webView.addJavascriptInterface(fileBridge, "MessFiles");
        }
        WebSettings settings = webView.getSettings();
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep the JS bridge confined to exact packaged HTTPS-style asset paths.
                Uri url = request.getUrl();
                return url == null || !"https".equals(url.getScheme())
                        || !"appassets.androidplatform.net".equals(url.getHost())
                        || url.getUserInfo() != null || url.getPort() != -1
                        || url.getQuery() != null || url.getFragment() != null
                        || url.getPath() == null || !url.getPath().startsWith("/assets/");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith(APP_URL)) {
                    // Mark the one-time migration only after the HTTPS-origin page has loaded
                    // and its fragment bootstrap had a chance to copy the prior local state.
                    getPreferences(MODE_PRIVATE).edit().putBoolean(MIGRATED_KEY, true).apply();
                    webView.clearHistory();
                }
            }
        });
        webView.loadUrl(APP_URL + fragment);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleHandler.removeCallbacks(refreshUiActiveMarker);
        refreshUiActiveMarker.run();
    }

    @Override
    protected void onStop() {
        lifecycleHandler.removeCallbacks(refreshUiActiveMarker);
        getSharedPreferences(BackgroundSyncWorker.PREFS, MODE_PRIVATE).edit()
                .putLong(BackgroundSyncWorker.KEY_UI_ACTIVE_AT, System.currentTimeMillis()).apply();
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (fileBridge != null && fileBridge.onActivityResult(requestCode, resultCode, data)) return;
        if (firebaseBridge != null) firebaseBridge.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
