package dev.weather.oneplus;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.net.URISyntaxException;

/** Standard Android WebView for pages requested by the weather UI. */
public final class WeatherWebActivity extends Activity {
    public static final String EXTRA_URL = "weather_page_url";
    public static final String EXTRA_TITLE = "weather_page_title";
    private WebView web;
    private ProgressBar progress;
    private TextView title;
    private LinearLayout error;
    private String initialUrl;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        initialUrl = AndroidWeatherPages.webUrl(getIntent().getStringExtra(EXTRA_URL));
        if (initialUrl == null) { finish(); return; }
        boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
        int background = dark ? Color.rgb(20,20,20) : Color.WHITE;
        int foreground = dark ? Color.WHITE : Color.BLACK;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText("‹");
        back.setContentDescription("返回"); back.setOnClickListener(v -> onBackPressed());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(52)));
        title = new TextView(this); title.setTextColor(foreground); title.setTextSize(20);
        title.setSingleLine(true); title.setText(getIntent().getStringExtra(EXTRA_TITLE));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        error = new LinearLayout(this); error.setOrientation(LinearLayout.VERTICAL); error.setGravity(Gravity.CENTER);
        TextView message = new TextView(this); message.setTextColor(foreground); message.setText("网页暂时无法加载，请检查网络后重试");
        Button retry = new Button(this); retry.setText("重新加载");
        retry.setOnClickListener(v -> {
            error.setVisibility(View.GONE);
            if(web==null)requestWebView(root,background,state);
            else{web.setVisibility(View.VISIBLE);web.reload();}
        });
        error.addView(message); error.addView(retry); error.setVisibility(View.GONE);
        root.addView(error, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        requestWebView(root,background,state);
    }

    private void requestWebView(LinearLayout root,int background,Bundle state){
        progress.setVisibility(View.VISIBLE);
        WeatherWebStartup.whenReady(this,()->{
            if(isFinishing()||isDestroyed())return;
            createWebView(root,background,state);
        },failure->{
            if(isFinishing()||isDestroyed())return;
            progress.setVisibility(View.GONE);
            error.setVisibility(View.VISIBLE);
            android.util.Log.e("WeatherPages","WebView engine startup failed",failure);
        });
    }

    private void createWebView(LinearLayout root,int background,Bundle state){
        web = new WebView(this);
        web.setBackgroundColor(background);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setTextZoom(100); settings.setLoadWithOverviewMode(true); settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE); }
            @Override public void onReceivedTitle(WebView view, String value) {
                if (value != null && !value.isEmpty()) title.setText(value);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return route(request.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return route(url); }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                error.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); progress.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError reason) {
                if (request.isForMainFrame()) { progress.setVisibility(View.GONE); web.setVisibility(View.GONE); error.setVisibility(View.VISIBLE); }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        if (state == null || web.restoreState(state) == null) web.loadUrl(initialUrl);
    }

    private boolean route(String url) {
        if (AndroidWeatherPages.webUrl(url) != null) return false;
        if (url != null && url.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = AndroidWeatherPages.webUrl(intent.getStringExtra("browser_fallback_url"));
                if (fallback != null) web.loadUrl(fallback);
            } catch (URISyntaxException ignored) { }
        }
        // App promotion deep links cannot leave the embedded weather page.
        return true;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else finish(); }
    @Override protected void onSaveInstanceState(Bundle state) { if (web != null) web.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.setWebChromeClient(null); web.setWebViewClient(null); web.destroy(); web = null; }
        super.onDestroy();
    }
}
