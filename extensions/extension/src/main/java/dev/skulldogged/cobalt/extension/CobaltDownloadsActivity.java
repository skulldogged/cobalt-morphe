package dev.skulldogged.cobalt.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.webkit.MimeTypeMap;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CobaltDownloadsActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 500;
    private static final int MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService THUMBNAIL_EXECUTOR =
            Executors.newFixedThreadPool(2);
    private static final Set<String> THUMBNAILS_LOADING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> THUMBNAIL_FAILURES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final LruCache<String, Bitmap> THUMBNAIL_CACHE =
            new LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private LinearLayout list;
    private boolean dark;
    private int background;
    private int card;
    private int primaryText;
    private int secondaryText;
    private String lastSignature = "";
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background = dark ? Color.rgb(15, 15, 15) : Color.rgb(255, 255, 255);
        card = dark ? Color.rgb(38, 38, 38) : Color.rgb(245, 245, 245);
        primaryText = dark ? Color.WHITE : Color.rgb(15, 15, 15);
        secondaryText = dark ? Color.rgb(185, 185, 185) : Color.rgb(95, 95, 95);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(16), dp(8));

        TextView back = text("‹", 40, primaryText);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(ignored -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));

        TextView title = text("Downloads", 22, primaryText);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lastSignature = "";
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void refresh() {
        List<CobaltDownloadRepository.Record> records = CobaltDownloadRepository.list(this);
        String signature = signature(records);
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        list.removeAllViews();

        if (records.isEmpty()) {
            TextView emptyTitle = text("No cobalt downloads yet", 20, primaryText);
            emptyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            emptyTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleParams = wrap();
            titleParams.topMargin = dp(96);
            list.addView(emptyTitle, titleParams);

            TextView emptyBody = text(
                    "Files downloaded with the YouTube download button will appear here.",
                    15,
                    secondaryText
            );
            emptyBody.setGravity(Gravity.CENTER);
            emptyBody.setPadding(dp(24), dp(12), dp(24), 0);
            list.addView(emptyBody, wrap());
            return;
        }

        for (CobaltDownloadRepository.Record record : records) {
            list.addView(buildCard(record), cardParams());
        }
    }

    private View buildCard(CobaltDownloadRepository.Record record) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card);
        shape.setCornerRadius(dp(12));
        container.setBackground(shape);

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.TOP);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER);
        thumbnail.setImageResource(android.R.drawable.ic_media_play);
        thumbnail.setColorFilter(secondaryText);
        thumbnail.setContentDescription("Thumbnail for " + displayName(record));
        GradientDrawable thumbnailBackground = new GradientDrawable();
        thumbnailBackground.setColor(dark ? Color.rgb(24, 24, 24) : Color.rgb(225, 225, 225));
        thumbnailBackground.setCornerRadius(dp(8));
        thumbnail.setBackground(thumbnailBackground);
        thumbnail.setClipToOutline(true);
        summary.addView(thumbnail, new LinearLayout.LayoutParams(dp(144), dp(81)));
        loadThumbnail(thumbnail, record);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        detailsParams.leftMargin = dp(12);
        summary.addView(details, detailsParams);

        TextView filename = text(displayName(record), 16, primaryText);
        filename.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        filename.setMaxLines(2);
        details.addView(filename, wrap());

        TextView status = text(statusText(record), 14, secondaryText);
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.topMargin = dp(6);
        details.addView(status, statusParams);

        TextView date = text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(record.createdAt)),
                12,
                secondaryText
        );
        LinearLayout.LayoutParams dateParams = wrap();
        dateParams.topMargin = dp(8);
        details.addView(date, dateParams);
        container.addView(summary, wrap());

        if (CobaltDownloadRepository.STATE_AUTHORIZING.equals(record.state)
                || CobaltDownloadRepository.STATE_PREPARING.equals(record.state)
                || CobaltDownloadRepository.STATE_DOWNLOADING.equals(record.state)
                || CobaltDownloadRepository.STATE_FINALIZING.equals(record.state)) {
            ProgressBar progress = new ProgressBar(
                    this,
                    null,
                    android.R.attr.progressBarStyleHorizontal
            );
            boolean unknown = CobaltDownloadRepository.STATE_AUTHORIZING.equals(record.state)
                    || CobaltDownloadRepository.STATE_PREPARING.equals(record.state)
                    || (CobaltDownloadRepository.STATE_DOWNLOADING.equals(record.state)
                    && record.totalBytes <= 0);
            progress.setIndeterminate(unknown);
            progress.setMax(100);
            progress.setProgress(record.progress);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(4)
            );
            progressParams.topMargin = dp(12);
            container.addView(progress, progressParams);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsParams = wrap();
        actionsParams.topMargin = dp(8);

        if (CobaltDownloadRepository.STATE_COMPLETE.equals(record.state)) {
            actions.addView(action("Open", ignored -> open(record)));
            actions.addView(action("Delete", ignored -> confirmDelete(record)));
        } else if (CobaltDownloadRepository.STATE_FAILED.equals(record.state)) {
            actions.addView(action("Retry", ignored -> retry(record)));
            actions.addView(action("Remove", ignored -> remove(record)));
        }
        if (actions.getChildCount() > 0) {
            container.addView(actions, actionsParams);
        }
        return container;
    }

    private void loadThumbnail(
            ImageView imageView,
            CobaltDownloadRepository.Record record
    ) {
        String videoId = videoIdFrom(record.sourceUrl);
        if (videoId == null) {
            return;
        }
        imageView.setTag(videoId);

        Bitmap cached = THUMBNAIL_CACHE.get(videoId);
        if (cached != null) {
            applyThumbnail(imageView, videoId, cached);
            return;
        }
        if (THUMBNAIL_FAILURES.contains(videoId) || !THUMBNAILS_LOADING.add(videoId)) {
            return;
        }

        File cacheFile = new File(
                new File(getCacheDir(), "cobalt-thumbnails"),
                videoId + ".jpg"
        );
        THUMBNAIL_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (cacheFile.isFile()) {
                    bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                    if (bitmap == null) {
                        //noinspection ResultOfMethodCallIgnored
                        cacheFile.delete();
                    }
                }
                if (bitmap == null) {
                    bitmap = downloadThumbnail(videoId);
                    if (bitmap != null) {
                        cacheThumbnail(cacheFile, bitmap);
                    }
                }
            } catch (Exception ignored) {
                // Keep the placeholder when a thumbnail is unavailable.
            } finally {
                THUMBNAILS_LOADING.remove(videoId);
            }

            if (bitmap == null) {
                THUMBNAIL_FAILURES.add(videoId);
                return;
            }
            THUMBNAIL_CACHE.put(videoId, bitmap);
            Bitmap loaded = bitmap;
            handler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                applyThumbnail(imageView, videoId, loaded);
                if (resumed) {
                    lastSignature = "";
                    refresh();
                }
            });
        });
    }

    private Bitmap downloadThumbnail(String videoId) throws Exception {
        URL url = new URL("https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "image/jpeg,image/*;q=0.8");
        connection.setRequestProperty("User-Agent", "Cobalt-Morphe/1.0");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_THUMBNAIL_BYTES) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void cacheThumbnail(File cacheFile, Bitmap bitmap) {
        File directory = cacheFile.getParentFile();
        if (directory == null
                || (!directory.isDirectory() && !directory.mkdirs())) {
            return;
        }
        try (FileOutputStream output = new FileOutputStream(cacheFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
            }
        } catch (Exception ignored) {
            // Disk caching is an optimization; the in-memory image still works.
        }
    }

    private void applyThumbnail(ImageView imageView, String videoId, Bitmap bitmap) {
        if (!videoId.equals(imageView.getTag())) {
            return;
        }
        imageView.clearColorFilter();
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageBitmap(bitmap);
    }

    private String videoIdFrom(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(sourceUrl);
            String videoId = uri.getQueryParameter("v");
            if (videoId == null && "youtu.be".equalsIgnoreCase(uri.getHost())) {
                videoId = uri.getLastPathSegment();
            }
            return videoId != null && videoId.matches("[A-Za-z0-9_-]{6,20}")
                    ? videoId
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String statusText(CobaltDownloadRepository.Record record) {
        if (CobaltDownloadRepository.STATE_AUTHORIZING.equals(record.state)) {
            return "Waiting for cobalt verification…";
        }
        if (CobaltDownloadRepository.STATE_PREPARING.equals(record.state)) {
            return "Preparing download…";
        }
        if (CobaltDownloadRepository.STATE_FINALIZING.equals(record.state)) {
            return "Finalizing MP4… " + record.progress + "%";
        }
        if (CobaltDownloadRepository.STATE_COMPLETE.equals(record.state)) {
            return record.totalBytes > 0 ? "Downloaded · " + formatBytes(record.totalBytes) : "Downloaded";
        }
        if (CobaltDownloadRepository.STATE_FAILED.equals(record.state)) {
            return "Failed" + (record.error == null ? "" : " · " + record.error);
        }
        if (record.totalBytes > 0) {
            return "Downloading · " + record.progress + "% · "
                    + formatBytes(record.receivedBytes) + " / " + formatBytes(record.totalBytes);
        }
        return "Downloading… " + formatBytes(record.receivedBytes);
    }

    private String displayName(CobaltDownloadRepository.Record record) {
        String value = record.filename;
        return value == null || value.trim().isEmpty() ? "YouTube video" : value;
    }

    private void open(CobaltDownloadRepository.Record record) {
        if (record.outputUri == null) {
            toast("The downloaded file could not be found");
            return;
        }
        Uri uri = Uri.parse(record.outputUri);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType(record, uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            toast("No app is available to open this download");
        }
    }

    private String mimeType(CobaltDownloadRepository.Record record, Uri uri) {
        try {
            String contentType = getContentResolver().getType(uri);
            if (contentType != null
                    && !contentType.isEmpty()
                    && !"application/octet-stream".equals(contentType)) {
                return contentType;
            }
        } catch (RuntimeException ignored) {
            // Fall back to the cobalt-provided filename below.
        }

        String filename = record.filename == null ? "" : record.filename;
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < filename.length()) {
            String contentType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    filename.substring(dot + 1).toLowerCase(Locale.US)
            );
            if (contentType != null) {
                return contentType;
            }
        }
        return "*/*";
    }

    private void confirmDelete(CobaltDownloadRepository.Record record) {
        new AlertDialog.Builder(this)
                .setTitle("Delete download?")
                .setMessage(displayName(record))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    CobaltDownloadRepository.delete(this, record.id, true);
                    lastSignature = "";
                    refresh();
                })
                .show();
    }

    private void remove(CobaltDownloadRepository.Record record) {
        CobaltDownloadRepository.delete(this, record.id, false);
        lastSignature = "";
        refresh();
    }

    private void retry(CobaltDownloadRepository.Record record) {
        if (!CobaltDownloader.retry(record.id)) {
            toast("Another cobalt download is already running");
        }
        lastSignature = "";
        refresh();
    }

    private String signature(List<CobaltDownloadRepository.Record> records) {
        StringBuilder builder = new StringBuilder();
        for (CobaltDownloadRepository.Record record : records) {
            builder.append(record.id).append('|')
                    .append(record.state).append('|')
                    .append(record.progress).append('|')
                    .append(record.receivedBytes).append('|')
                    .append(record.totalBytes).append('|')
                    .append(record.filename).append('|')
                    .append(record.outputUri).append('|')
                    .append(record.error).append(';');
        }
        return builder.toString();
    }

    private Button action(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.rgb(255, 45, 45));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = wrap();
        params.bottomMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < units.length - 1);
        return String.format(Locale.US, value >= 10 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
