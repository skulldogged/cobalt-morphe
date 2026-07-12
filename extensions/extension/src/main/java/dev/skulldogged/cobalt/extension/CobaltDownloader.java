package dev.skulldogged.cobalt.extension;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CobaltDownloader {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean REQUEST_ACTIVE = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Context applicationContext;

    private CobaltDownloader() {
    }

    public static void initialize(Object application) {
        if (application instanceof Context) {
            applicationContext = ((Context) application).getApplicationContext();
        }
    }

    public static boolean onDownloadButtonClick(String videoId) {
        final Context context = applicationContext;
        if (context == null || !isValidVideoId(videoId)) {
            return false;
        }

        if (!REQUEST_ACTIVE.compareAndSet(false, true)) {
            showToast(context, "A cobalt download is already being prepared");
            return true;
        }

        showToast(context, "Preparing cobalt download…");
        final String sourceUrl = "https://www.youtube.com/watch?v=" + videoId;

        EXECUTOR.execute(() -> {
            try {
                CobaltResponse response = CobaltClient.request(sourceUrl);
                enqueueDownload(context, response);
                showToast(context, "Download started");
            } catch (Exception exception) {
                showToast(context, "Download failed: " + safeMessage(exception));
            } finally {
                REQUEST_ACTIVE.set(false);
            }
        });

        return true;
    }

    private static void enqueueDownload(Context context, CobaltResponse response)
            throws Exception {
        Uri uri = Uri.parse(response.url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new CobaltException("cobalt returned a non-HTTPS URL");
        }

        String filename = sanitizeFilename(response.filename);
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(filename)
                .setDescription("Downloaded through cobalt")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            throw new CobaltException("Android DownloadManager is unavailable");
        }
        manager.enqueue(request);
    }

    private static boolean isValidVideoId(String videoId) {
        return videoId != null && videoId.matches("[A-Za-z0-9_-]{6,20}");
    }

    private static String sanitizeFilename(String filename) {
        String safe = filename == null ? "" : filename
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\r\\n]", " ")
                .trim();
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            return "cobalt-download.mp4";
        }
        return safe.length() > 180 ? safe.substring(0, 180) : safe;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getClass().getSimpleName();
        }
        return message.toLowerCase(Locale.US);
    }

    private static void showToast(Context context, String message) {
        MAIN_HANDLER.post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }
}
