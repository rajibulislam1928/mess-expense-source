package com.messexpense.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Native Storage Access Framework bridge for genuine on-device .xlsx backup files. */
final class FileBridge {
    static final int REQUEST_EXPORT_XLSX = 7403;
    static final int REQUEST_IMPORT_XLSX = 7404;
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_IMPORT_BYTES = 16 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private byte[] pendingExport;
    private boolean activityPickerOpen;

    FileBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void exportXlsx(String stateJson) {
        try {
            byte[] bytes = XlsxBackup.exportState(stateJson);
            activity.runOnUiThread(() -> {
                if (activityPickerOpen) { message("Finish the current file picker first.", false); return; }
                pendingExport = bytes;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(XLSX_MIME);
                intent.putExtra(Intent.EXTRA_TITLE, "mess-expense-backup.xlsx");
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                activityPickerOpen = true;
                try { activity.startActivityForResult(intent, REQUEST_EXPORT_XLSX); }
                catch (Exception e) { activityPickerOpen = false; pendingExport = null; message("Could not open the device save screen.", false); }
            });
        } catch (Exception e) {
            message("Could not prepare the Excel backup: " + safeMessage(e), false);
        }
    }

    @JavascriptInterface
    public void shareText(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 1000) return;
        activity.runOnUiThread(() -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            try { activity.startActivity(Intent.createChooser(send, "Share Mess Expense invite")); }
            catch (Exception ignored) { message("Could not open the share menu.", false); }
        });
    }

    @JavascriptInterface
    public void importXlsx() {
        activity.runOnUiThread(() -> {
            if (activityPickerOpen) { message("Finish the current file picker first.", false); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(XLSX_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activityPickerOpen = true;
            try { activity.startActivityForResult(intent, REQUEST_IMPORT_XLSX); }
            catch (Exception e) { activityPickerOpen = false; message("Could not open the device file picker.", false); }
        });
    }

    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT_XLSX && requestCode != REQUEST_IMPORT_XLSX) return false;
        activityPickerOpen = false;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pendingExport = null;
            message("File operation cancelled.", false);
            return true;
        }
        if (requestCode == REQUEST_EXPORT_XLSX) {
            byte[] bytes = pendingExport;
            pendingExport = null;
            if (bytes == null) { message("The Excel backup was not ready. Please try again.", false); return true; }
            try (OutputStream stream = activity.getContentResolver().openOutputStream(uri, "w")) {
                if (stream == null) throw new IllegalStateException("Device storage did not open the selected file.");
                stream.write(bytes);
                stream.flush();
                message("Excel backup saved on this device.", true);
            } catch (Exception e) {
                message("Could not save the Excel backup: " + safeMessage(e), false);
            }
        } else {
            try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IllegalStateException("Device storage did not open the selected file.");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0, n;
                while ((n = stream.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_IMPORT_BYTES) throw new IllegalArgumentException("This workbook is too large to import safely.");
                    bytes.write(buffer, 0, n);
                }
                JSONObject state = XlsxBackup.importState(new ByteArrayInputStream(bytes.toByteArray()));
                String json = JSONObject.quote(state.toString());
                activity.runOnUiThread(() -> webView.evaluateJavascript("if(window.onXlsxImportReady)window.onXlsxImportReady(" + json + ");", null));
            } catch (Exception e) {
                message("Could not import this Excel backup: " + safeMessage(e), false);
            }
        }
        return true;
    }

    private void message(String text, boolean success) {
        String script = "if(window.onNativeBackupMessage)window.onNativeBackupMessage(" + JSONObject.quote(text) + "," + success + ");";
        activity.runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(script, null); });
    }

    private String safeMessage(Throwable e) {
        String message = e.getLocalizedMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
