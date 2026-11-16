package com.benyoriki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Cangkang (shell) Android untuk benyoriki.com.
 *
 * - Memuat situs live. Service worker milik situs (sw.js) menangani cache offline
 *   dan pembaruan konten otomatis.
 * - Jika pertama kali dibuka tanpa internet, memakai salinan situs yang ikut dikemas di APK.
 * - Navigasi D-pad remote TV lewat assets/tvnav.js.
 * - Pembaruan APK otomatis lewat GitHub Releases (lihat Updater).
 */
public class MainActivity extends Activity {

    private static final String SITE_HOST = "benyoriki.com";
    private static final String ASSET_HOST = "appassets.androidplatform.net";

    private WebView web;
    private ProgressBar bar;
    private WebViewAssetLoader loader;
    private boolean isTv;
    private boolean offlineShown;
    private long lastBack;
    private String tvJs = "";
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        isTv = (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        if (isTv) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvJs = readAsset("tvnav.js");

        loader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/", this::handleBundledSite)
                .build();

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, (int) (4 * getResources().getDisplayMetrics().density));
        lp.gravity = Gravity.TOP;
        root.addView(bar, lp);
        setContentView(root);

        setupWebView();
        registerNetwork();

        if (b != null) web.restoreState(b);
        else web.loadUrl(BuildConfig.SITE_URL);
        web.requestFocus();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setUserAgentString(s.getUserAgentString() + " BenyorikiApp/" + BuildConfig.VERSION_NAME + (isTv ? " TV" : ""));
        web.setBackgroundColor(0xFF05070D);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                String sch = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.equals(SITE_HOST) || host.endsWith("." + SITE_HOST) || host.equals(ASSET_HOST)) return false;
                if (sch.equals("http") || sch.equals("https")) {
                    if (isTv) return false;          // TV: biasanya tanpa browser, buka di dalam app
                    return openExternal(u);
                }
                return openExternal(u);              // tel:, mailto:, intent:, dll.
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                v.evaluateJavascript(tvJs, null);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && !offlineShown && !ASSET_HOST.equals(r.getUrl().getHost())) showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Batal", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });
    }

    /* ── Salinan situs yang ikut dikemas di APK (cadangan offline pertama kali) ── */
    private WebResourceResponse handleBundledSite(String path) {
        try {
            if (path.isEmpty() || path.equals("/")) path = "index.html";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.startsWith("/")) path = path.substring(1);
            if (path.equals("sw.js")) return notFound();
            InputStream in = getAssets().open("site/" + path);
            return new WebResourceResponse(mime(path), path.endsWith(".webp") ? null : "utf-8", in);
        } catch (Exception e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new java.util.HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(path).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "js": return "application/javascript";
            case "json": return "application/json";
            case "css": return "text/css";
            case "html": return "text/html";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
        }
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private void showOffline() {
        offlineShown = true;
        Toast.makeText(this, "Offline — menampilkan salinan tersimpan", Toast.LENGTH_LONG).show();
        web.loadUrl("https://" + ASSET_HOST + "/index.html");
    }

    /* ── Pantau koneksi: begitu online lagi, muat ulang situs live ── */
    private void registerNetwork() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                runOnUiThread(() -> {
                    web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                    if (offlineShown) {
                        offlineShown = false;
                        web.loadUrl(BuildConfig.SITE_URL);
                    }
                    Updater.maybeCheck(MainActivity.this);
                });
            }

            @Override
            public void onLost(Network n) {
                runOnUiThread(() -> web.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK));
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCb);
        } catch (Exception ignored) {
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager c = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = c.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : c.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /* ── Remote TV ── */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO
                || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_BOOKMARK) {
            if (e.getAction() == KeyEvent.ACTION_UP) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__tvBack?window.__tvBack():false", v -> {
            if ("true".equals(v)) return;                       // modal ditutup oleh halaman
            if (web.canGoBack() && !offlineShown) { web.goBack(); return; }
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) finish();
            else {
                lastBack = now;
                Toast.makeText(this, "Tekan Kembali lagi untuk keluar", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showMenu() {
        String[] items = {"Muat ulang", "Periksa pembaruan aplikasi", "Hapus cache & muat ulang",
                "Versi " + BuildConfig.VERSION_NAME, "Keluar dari aplikasi"};
        new AlertDialog.Builder(this).setTitle("Benyoriki").setItems(items, (d, i) -> {
            switch (i) {
                case 0: offlineShown = false; web.loadUrl(BuildConfig.SITE_URL); break;
                case 1: Updater.check(this, true); break;
                case 2: web.clearCache(true); offlineShown = false; web.loadUrl(BuildConfig.SITE_URL); break;
                case 4: finish(); break;
                default: break;
            }
        }).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Updater.resumePending(this);
        Updater.maybeCheck(this);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        web.destroy();
        super.onDestroy();
    }
}
