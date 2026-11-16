package com.benyoriki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Update APK otomatis via GitHub Releases.
 * Workflow membuat rilis bertag "build-<nomor>" dan nomor itu = versionCode.
 * Android tidak mengizinkan instal senyap, jadi pengguna tetap menekan "Instal" sekali.
 */
final class Updater {
    private static final long INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static volatile boolean busy;
    private static volatile boolean cancelled;
    private static File pendingApk;

    private Updater() { }

    static void maybeCheck(Activity a) {
        SharedPreferences p = a.getSharedPreferences("updater", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("last", 0) < INTERVAL_MS) return;
        check(a, false);
    }

    static void check(final Activity a, final boolean manual) {
        if (BuildConfig.GITHUB_REPO.startsWith("OWNER/")) {
            if (manual) toast(a, "Repo GitHub belum diatur (lihat README)");
            return;
        }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "BenyorikiApp");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                a.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                        .putLong("last", System.currentTimeMillis()).apply();

                String digits = j.optString("tag_name", "").replaceAll("\\D+", "");
                int remote = digits.isEmpty() ? 0 : Integer.parseInt(digits);
                String apkUrl = null;
                JSONArray assets = j.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.optString("name").endsWith(".apk")) { apkUrl = as.getString("browser_download_url"); break; }
                }
                if (remote > BuildConfig.VERSION_CODE && apkUrl != null) {
                    final String url = apkUrl;
                    final String name = j.optString("name", j.optString("tag_name"));
                    a.runOnUiThread(() -> promptUpdate(a, name, url));
                } else if (manual) {
                    toast(a, "Aplikasi sudah versi terbaru (" + BuildConfig.VERSION_NAME + ")");
                }
            } catch (Exception e) {
                if (manual) toast(a, "Gagal memeriksa pembaruan: " + e.getMessage());
            } finally {
                busy = false;
            }
        }).start();
    }

    private static void promptUpdate(Activity a, String name, String url) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a)
                .setTitle("Pembaruan tersedia")
                .setMessage("Versi baru: " + name + "\nVersi terpasang: " + BuildConfig.VERSION_NAME
                        + "\n\nUnduh dan pasang sekarang?")
                .setPositiveButton("Perbarui", (d, w) -> download(a, url))
                .setNegativeButton("Nanti", null)
                .show();
    }

    private static void download(final Activity a, final String url) {
        cancelled = false;
        final ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setPadding(48, 32, 48, 32);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Mengunduh pembaruan…")
                .setView(pb)
                .setNegativeButton("Batal", (d, w) -> cancelled = true)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            File out = new File(a.getCacheDir(), "apk/update.apk");
            try {
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "BenyorikiApp");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n, last = -1;
                    while (!cancelled && (n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != last) { last = pct; a.runOnUiThread(() -> pb.setProgress(pct)); }
                    }
                }
                a.runOnUiThread(dlg::dismiss);
                if (cancelled) { //noinspection ResultOfMethodCallIgnored
                    out.delete(); return; }
                a.runOnUiThread(() -> install(a, out));
            } catch (Exception e) {
                a.runOnUiThread(dlg::dismiss);
                toast(a, "Unduhan gagal: " + e.getMessage());
            }
        }).start();
    }

    private static void install(Activity a, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            new AlertDialog.Builder(a)
                    .setTitle("Izin instal diperlukan")
                    .setMessage("Aktifkan \"Izinkan dari sumber ini\" untuk Benyoriki, lalu tekan Kembali. Instalasi akan dilanjutkan otomatis.")
                    .setPositiveButton("Buka pengaturan", (d, w) -> a.startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))))
                    .setNegativeButton("Batal", null)
                    .show();
            return;
        }
        pendingApk = null;
        try {
            Uri uri = FileProvider.getUriForFile(a, BuildConfig.APPLICATION_ID + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            toast(a, "Tidak bisa membuka installer: " + e.getMessage());
        }
    }

    /** Dipanggil saat app kembali ke depan: lanjutkan instal jika izin baru saja diberikan. */
    static void resumePending(Activity a) {
        if (pendingApk != null && pendingApk.exists()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || a.getPackageManager().canRequestPackageInstalls())) {
            install(a, pendingApk);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString("UTF-8");
    }

    private static void toast(Activity a, String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
    }
}
