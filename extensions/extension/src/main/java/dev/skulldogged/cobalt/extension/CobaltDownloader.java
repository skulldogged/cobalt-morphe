package dev.skulldogged.cobalt.extension;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CobaltDownloader {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean DOWNLOAD_ACTIVE = new AtomicBoolean(false);

    private static volatile Context applicationContext;

    private CobaltDownloader() {
    }

    public static void initialize(Object application) {
        if (application instanceof Context) {
            applicationContext = ((Context) application).getApplicationContext();
            CobaltSettings.initialize(applicationContext);
        }
    }

    public static boolean onDownloadButtonClick(String videoId) {
        final Context context = applicationContext;
        if (context == null || !CobaltSettings.isEnabled() || !isValidVideoId(videoId)) {
            return false;
        }
        if (!DOWNLOAD_ACTIVE.compareAndSet(false, true)) {
            showToast(context, "A cobalt download is already running");
            return true;
        }

        Intent intent = new Intent(context, CobaltDownloadService.class)
                .putExtra(
                        CobaltDownloadService.EXTRA_SOURCE_URL,
                        "https://www.youtube.com/watch?v=" + videoId
                );
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException exception) {
            DOWNLOAD_ACTIVE.set(false);
            showToast(context, "Could not start the cobalt download");
            return true;
        }
        showToast(context, "Preparing cobalt download…");

        return true;
    }

    private static boolean isValidVideoId(String videoId) {
        return videoId != null && videoId.matches("[A-Za-z0-9_-]{6,20}");
    }

    static void onJobFinished() {
        DOWNLOAD_ACTIVE.set(false);
    }

    private static void showToast(Context context, String message) {
        MAIN_HANDLER.post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }
}
