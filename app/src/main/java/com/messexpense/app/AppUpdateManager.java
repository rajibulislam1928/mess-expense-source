package com.messexpense.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.URLUtil;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks public GitHub APK releases and opens Android's normal, user-confirmed installer. */
final class AppUpdateManager {
    private static final String API = "https://api.github.com/repos/rajibulislam1928/mess-expense-apk/releases?per_page=20";
    private static final String PREFS = "mess_expense_update";
    private static final String KEY_APK = "pending_apk";
    private static final String KEY_VERSION = "pending_version";
    private static final int REQUEST_UNKNOWN_SOURCES = 7101;
    private static final int REQUEST_INSTALL = 7102;
    private static final long MAX_APK_BYTES = 50L * 1024L * 1024L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Pattern BUILD = Pattern.compile("(?i)build[-_ ]?(\\d+)");
    private static final Pattern VERSION = Pattern.compile("(?i)v(\\d+(?:\\.\\d+){1,3})");

    private final Activity activity;
    private boolean checking;
    private boolean hasChecked;
    private boolean dialogShown;
    private Release pendingRelease;
    private android.app.ProgressDialog downloadDialog;
    private File readyUpdate;

    AppUpdateManager(Activity activity) { this.activity = activity; }

    void onAppReady() {
        cleanInstalledUpdate();
        checkForUpdate();
    }

    void onResume() { cleanInstalledUpdate(); }

    void onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQUEST_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) {
                if (pendingRelease != null) downloadAndInstall(pendingRelease);
            }
        } else if (requestCode == REQUEST_INSTALL) {
            cleanInstalledUpdate();
        }
    }

    private void checkForUpdate() {
        if (checking || hasChecked || dialogShown || activity.isFinishing()) return;
        checking = true;
        hasChecked = true;
        IO.execute(() -> {
            Release release = null;
            String error = null;
            try { release = findNewestRelease(); }
            catch (Exception e) { error = e.getMessage(); }
            final Release found = release;
            final String failure = error;
            activity.runOnUiThread(() -> {
                checking = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (found != null && found.buildNumber > currentVersionCode()) showUpdate(found);
                // Updates are optional; a transient network/API failure should not interrupt use.
            });
        });
    }

    private Release findNewestRelease() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "MessExpense-Android");
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("Release check returned HTTP " + code);
            String body = readLimited(connection.getInputStream(), 1_000_000);
            JSONArray releases = new JSONArray(body);
            Release best = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject item = releases.getJSONObject(i);
                if (item.optBoolean("draft", true)) continue;
                String tag = item.optString("tag_name", "");
                Matcher matcher = BUILD.matcher(tag);
                if (!matcher.find()) continue;
                int build;
                try { build = Integer.parseInt(matcher.group(1)); } catch (NumberFormatException ignored) { continue; }
                JSONArray assets = item.optJSONArray("assets");
                if (assets == null) continue;
                String apkUrl = null;
                String apkName = "";
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    String name = asset.optString("name", "");
                    String url = asset.optString("browser_download_url", "");
                    if (name.toLowerCase().endsWith(".apk") && safeReleaseUrl(url)) {
                        apkUrl = url; apkName = name; break;
                    }
                }
                if (apkUrl == null) continue;
                if (best == null || build > best.buildNumber) {
                    Matcher versionMatcher = VERSION.matcher(tag);
                    String version = versionMatcher.find() ? versionMatcher.group(1) : tag;
                    best = new Release(build, version, tag, apkName, apkUrl,
                            item.optString("body", ""));
                }
            }
            return best;
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static boolean safeReleaseUrl(String value) {
        if (!URLUtil.isHttpsUrl(value)) return false;
        try {
            URL url = new URL(value);
            return "github.com".equalsIgnoreCase(url.getHost())
                    && url.getPath().startsWith("/rajibulislam1928/mess-expense-apk/releases/download/");
        } catch (Exception e) { return false; }
    }

    private void showUpdate(Release release) {
        if (dialogShown || activity.isFinishing()) return;
        dialogShown = true;
        pendingRelease = release;
        String message = "Version " + release.versionName + " (build " + release.buildNumber + ") is available.\n\nInstall it now?";
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Mess Expense update")
                .setMessage(message)
                .setPositiveButton("Install", (dialog, which) -> beginInstall(release))
                .setNegativeButton("Cancel", (dialog, which) -> { pendingRelease = null; })
                .setOnCancelListener(dialog -> pendingRelease = null)
                .show();
    }

    private void beginInstall(Release release) {
        pendingRelease = release;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, REQUEST_UNKNOWN_SOURCES);
            return;
        }
        downloadAndInstall(release);
    }

    private void downloadAndInstall(Release release) {
        pendingRelease = release;
        showDownloadProgress(release);
        IO.execute(() -> {
            File finalFile = null;
            String error = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create update cache");
                File part = new File(dir, "mess-expense-update.apk.part");
                finalFile = new File(dir, "mess-expense-update.apk");
                download(release.apkUrl, part);
                setDownloadMessage("Download complete. Verifying the APK…");
                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(part.getAbsolutePath(), 0);
                if (archive == null || !activity.getPackageName().equals(archive.packageName))
                    throw new IllegalStateException("Downloaded file is not a Mess Expense APK");
                long version = packageVersionCode(archive);
                if (version <= currentVersionCode() || version != release.buildNumber)
                    throw new IllegalStateException("APK version does not match the advertised update");
                if (finalFile.exists() && !finalFile.delete()) throw new IllegalStateException("Could not replace cached update");
                if (!part.renameTo(finalFile)) throw new IllegalStateException("Could not finish saving update");
                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                        .putString(KEY_APK, finalFile.getAbsolutePath()).putLong(KEY_VERSION, version).apply();
            } catch (Exception e) {
                error = e.getMessage() == null ? "Download or verification failed" : e.getMessage();
                try { new File(activity.getCacheDir(), "updates/mess-expense-update.apk.part").delete(); } catch (Exception ignored) { }
            }
            final File ready = finalFile;
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null || ready == null || !ready.isFile()) {
                    showDownloadFailure(failure == null ? "Could not prepare the update." : failure);
                    return;
                }
                showDownloadReady(ready, release);
            });
        });
    }

    private void showDownloadProgress(Release release) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(activity);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.setTitle("Downloading update");
        dialog.setMessage("Preparing version " + release.versionName + " (build " + release.buildNumber + ")…");
        dialog.setIndeterminate(true);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        downloadDialog = dialog;
        dialog.show();
    }

    private void updateDownloadProgress(long downloaded, long total) {
        activity.runOnUiThread(() -> {
            android.app.ProgressDialog dialog = downloadDialog;
            if (dialog == null || !dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
            if (total > 0L) {
                int percent = (int) Math.min(100L, (downloaded * 100L) / total);
                dialog.setIndeterminate(false);
                dialog.setMax(100);
                dialog.setProgress(percent);
                dialog.setMessage("Downloading update… " + percent + "%");
            } else {
                dialog.setIndeterminate(true);
                dialog.setMessage(String.format(Locale.US, "Downloading update… %.1f MB", downloaded / 1048576.0));
            }
        });
    }

    private void setDownloadMessage(String message) {
        activity.runOnUiThread(() -> {
            android.app.ProgressDialog dialog = downloadDialog;
            if (dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed()) {
                dialog.setIndeterminate(true);
                dialog.setMessage(message);
            }
        });
    }

    private void showDownloadReady(File apk, Release release) {
        android.app.ProgressDialog dialog = downloadDialog;
        downloadDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        new AlertDialog.Builder(activity)
                .setTitle("Update ready")
                .setMessage("Version " + release.versionName + " is downloaded and verified. Android will ask you to confirm installation.")
                .setPositiveButton("Open installer", (d, which) -> openInstaller(apk))
                .setNegativeButton("Not now", null)
                .setOnCancelListener(d -> { })
                .show();
    }

    private void showDownloadFailure(String message) {
        android.app.ProgressDialog dialog = downloadDialog;
        downloadDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        new AlertDialog.Builder(activity).setTitle("Download failed")
                .setMessage(message).setPositiveButton("Close", null).show();
    }

    private void download(String url, File destination) throws Exception {
        if (!safeReleaseUrl(url)) throw new IllegalStateException("Update URL is not an approved release download");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "MessExpense-Android");
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("APK download returned HTTP " + status);
            long length = connection.getContentLengthLong();
            if (length > MAX_APK_BYTES) throw new IllegalStateException("Update APK is larger than 50 MB");
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16384];
                long downloaded = 0;
                long lastUiUpdate = 0;
                int lastPercent = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > MAX_APK_BYTES) throw new IllegalStateException("Update APK is larger than 50 MB");
                    out.write(buffer, 0, read);
                    long now = android.os.SystemClock.elapsedRealtime();
                    int percent = length > 0L ? (int) Math.min(100L, (downloaded * 100L) / length) : -1;
                    if ((percent >= 0 && percent != lastPercent) || now - lastUiUpdate >= 300L) {
                        updateDownloadProgress(downloaded, length);
                        lastPercent = percent;
                        lastUiUpdate = now;
                    }
                }
                out.getFD().sync();
                if (downloaded < 1024) throw new IllegalStateException("Downloaded update is incomplete");
            }
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void openInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".updatefiles", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            activity.startActivityForResult(intent, REQUEST_INSTALL);
        } catch (Exception e) {
            new android.app.AlertDialog.Builder(activity).setTitle("Could not open installer")
                    .setMessage(e.getMessage() == null ? "Android could not open the update installer." : e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private void cleanInstalledUpdate() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String path = prefs.getString(KEY_APK, null);
        long expected = prefs.getLong(KEY_VERSION, -1L);
        if (path == null || expected <= 0 || currentVersionCode() < expected) return;
        try { new File(path).delete(); } catch (Exception ignored) { }
        prefs.edit().remove(KEY_APK).remove(KEY_VERSION).apply();
    }

    private static long packageVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private long currentVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return packageVersionCode(info);
        } catch (Exception e) { return 0; }
    }

    private static String readLimited(InputStream input, int max) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read; int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read; if (total > max) throw new IllegalStateException("Release metadata is too large");
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static final class Release {
        final int buildNumber; final String versionName; final String tag; final String apkName; final String apkUrl; final String notes;
        Release(int b, String v, String t, String n, String u, String d) {
            buildNumber=b; versionName=v; tag=t; apkName=n; apkUrl=u; notes=d;
        }
    }
}
