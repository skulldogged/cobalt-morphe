package dev.skulldogged.cobalt.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.webkit.MimeTypeMap;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private static final String ORIGINAL_PACKAGE = "com.google.android.youtube";
    private static final String TITLE_PREFERENCES = "cobalt_video_titles";
    private static final long REFRESH_INTERVAL_MS = 500;
    private static final int MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TITLE_RESPONSE_CHARS = 64 * 1024;
    private static final int MENU_INFO = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_RETRY = 3;
    private static final int MENU_REMOVE = 4;
    private static final ExecutorService BACKGROUND_EXECUTOR =
            Executors.newFixedThreadPool(2);
    private static final Set<String> THUMBNAILS_LOADING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> THUMBNAIL_FAILURES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> TITLES_LOADING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> TITLE_FAILURES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final LruCache<String, Bitmap> THUMBNAIL_CACHE =
            new LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private static final LruCache<String, String> TITLE_CACHE = new LruCache<>(100);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private LinearLayout list;
    private int background;
    private int card;
    private int primaryText;
    private int secondaryText;
    private String lastSignature = "";
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Boolean morpheDarkMode = morpheDarkMode();
        if (morpheDarkMode != null) {
            setTheme(morpheDarkMode
                    ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
        }
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        boolean darkMode = morpheDarkMode != null
                ? morpheDarkMode
                : (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int themedFallback = themeColor(android.R.attr.colorBackground,
                darkMode ? Color.BLACK : Color.WHITE);
        background = namedColor(darkMode ? "yt_black1" : "yt_white1", themedFallback);
        primaryText = isDark(background) ? Color.WHITE : Color.rgb(15, 15, 15);
        secondaryText = isDark(background)
                ? Color.rgb(185, 185, 185)
                : Color.rgb(95, 95, 95);
        card = blend(background, primaryText, isDark(background) ? 0.09f : 0.06f);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        int systemUi = getWindow().getDecorView().getSystemUiVisibility();
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (isDark(background)) {
            systemUi &= ~lightBars;
        } else {
            systemUi |= lightBars;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(16), 0);

        ImageButton back = new ImageButton(this);
        back.setImageDrawable(themeDrawable(android.R.attr.homeAsUpIndicator));
        back.setColorFilter(primaryText);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(ignored -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));

        TextView title = text("Downloads", 20, primaryText);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                dp(56),
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
        FrameLayout container = new FrameLayout(this);
        container.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card);
        shape.setCornerRadius(dp(14));
        container.setBackground(shape);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setContentDescription("Thumbnail for " + displayName(record));
        container.addView(thumbnail, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        loadThumbnail(thumbnail, record);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(166, 0, 0, 0));
        container.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        FrameLayout content = new FrameLayout(this);
        content.setPadding(dp(16), dp(10), dp(8), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);

        TextView filename = text(displayName(record), 17, Color.WHITE);
        filename.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        filename.setMaxLines(2);
        filename.setEllipsize(TextUtils.TruncateAt.END);
        loadVideoTitle(filename, record);
        details.addView(filename, wrap());
        top.addView(details, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        TextView overflow = text("⋮", 28, Color.WHITE);
        overflow.setGravity(Gravity.CENTER);
        overflow.setContentDescription("More options for " + displayName(record));
        overflow.setOnClickListener(view -> showOverflow(view, record));
        top.addView(overflow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);

        TextView status = text(statusText(record), 14, Color.rgb(230, 230, 230));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(status, wrap());

        TextView date = text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(record.createdAt)),
                12,
                Color.rgb(210, 210, 210)
        );
        LinearLayout.LayoutParams dateParams = wrap();
        dateParams.topMargin = dp(6);
        bottom.addView(date, dateParams);

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
            bottom.addView(progress, progressParams);
        }

        content.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));

        if (CobaltDownloadRepository.STATE_COMPLETE.equals(record.state)) {
            container.setOnClickListener(ignored -> open(record));
            container.setClickable(true);
            container.setFocusable(true);
        }
        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return container;
    }

    private void loadVideoTitle(
            TextView titleView,
            CobaltDownloadRepository.Record record
    ) {
        String videoId = videoIdFrom(record.sourceUrl);
        if (videoId == null) {
            return;
        }
        titleView.setTag(videoId);

        String cached = cachedTitle(videoId);
        if (cached != null) {
            titleView.setText(cached);
            return;
        }
        if (TITLE_FAILURES.contains(videoId) || !TITLES_LOADING.add(videoId)) {
            return;
        }

        BACKGROUND_EXECUTOR.execute(() -> {
            String title = null;
            try {
                title = downloadVideoTitle(videoId);
            } catch (Exception ignored) {
                // The filename-derived title remains usable while offline.
            } finally {
                TITLES_LOADING.remove(videoId);
            }

            if (title == null) {
                TITLE_FAILURES.add(videoId);
                return;
            }
            TITLE_CACHE.put(videoId, title);
            getSharedPreferences(TITLE_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(videoId, title)
                    .apply();
            String loaded = title;
            handler.post(() -> {
                if (!isDestroyed() && videoId.equals(titleView.getTag())) {
                    titleView.setText(loaded);
                }
            });
        });
    }

    private String cachedTitle(String videoId) {
        String title = TITLE_CACHE.get(videoId);
        if (title != null) {
            return title;
        }
        SharedPreferences preferences = getSharedPreferences(
                TITLE_PREFERENCES,
                MODE_PRIVATE
        );
        title = preferences.getString(videoId, null);
        if (title != null && !title.trim().isEmpty()) {
            TITLE_CACHE.put(videoId, title);
            return title;
        }
        return null;
    }

    private String downloadVideoTitle(String videoId) throws Exception {
        URL url = new URL(
                "https://www.youtube.com/oembed?url="
                        + "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D"
                        + videoId
                        + "&format=json"
        );
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Cobalt-Morphe/1.0");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || connection.getContentLengthLong() > MAX_TITLE_RESPONSE_CHARS) {
                return null;
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                char[] buffer = new char[2048];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    if (response.length() + read > MAX_TITLE_RESPONSE_CHARS) {
                        return null;
                    }
                    response.append(buffer, 0, read);
                }
            }
            String title = new JSONObject(response.toString()).optString("title", "").trim();
            return title.isEmpty() || title.length() > 500 ? null : title;
        } finally {
            connection.disconnect();
        }
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
        BACKGROUND_EXECUTOR.execute(() -> {
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
        String videoId = videoIdFrom(record.sourceUrl);
        String cached = videoId == null ? null : cachedTitle(videoId);
        if (cached != null) {
            return cached;
        }
        String value = record.filename;
        if (value == null || value.trim().isEmpty()) {
            return "YouTube video";
        }
        value = value.trim();
        int extension = value.lastIndexOf('.');
        if (extension > 0) {
            value = value.substring(0, extension);
        }
        int metadata = value.lastIndexOf(" (");
        if (metadata > 0 && value.endsWith(")")) {
            value = value.substring(0, metadata);
        }
        int author = value.lastIndexOf(" - ");
        if (author > 0) {
            value = value.substring(0, author);
        }
        return value.trim().isEmpty() ? "YouTube video" : value.trim();
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

    private void showOverflow(View anchor, CobaltDownloadRepository.Record record) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_INFO, 0, "Info");
        if (CobaltDownloadRepository.STATE_COMPLETE.equals(record.state)) {
            popup.getMenu().add(0, MENU_DELETE, 1, "Delete download");
        } else if (CobaltDownloadRepository.STATE_FAILED.equals(record.state)) {
            popup.getMenu().add(0, MENU_RETRY, 1, "Retry");
            popup.getMenu().add(0, MENU_REMOVE, 2, "Remove from list");
        }
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_INFO) {
                showInfo(record);
                return true;
            }
            if (item.getItemId() == MENU_DELETE) {
                confirmDelete(record);
                return true;
            }
            if (item.getItemId() == MENU_RETRY) {
                retry(record);
                return true;
            }
            if (item.getItemId() == MENU_REMOVE) {
                remove(record);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showInfo(CobaltDownloadRepository.Record record) {
        if (record.outputUri == null) {
            showInfoDialog(record, basicInfo(record));
            return;
        }
        BACKGROUND_EXECUTOR.execute(() -> {
            String details = mediaInfo(record);
            handler.post(() -> {
                if (!isDestroyed()) {
                    showInfoDialog(record, details);
                }
            });
        });
    }

    private void showInfoDialog(CobaltDownloadRepository.Record record, String details) {
        new AlertDialog.Builder(this)
                .setTitle(displayName(record))
                .setMessage(details)
                .setPositiveButton("Done", null)
                .show();
    }

    private String mediaInfo(CobaltDownloadRepository.Record record) {
        StringBuilder details = new StringBuilder(basicInfo(record));
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(this, Uri.parse(record.outputUri), null);
            int width = 0;
            int height = 0;
            int frameRate = 0;
            String videoCodec = null;
            String audioCodec = null;
            long durationUs = 0;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/") && videoCodec == null) {
                    videoCodec = codecName(mime);
                    width = integer(format, MediaFormat.KEY_WIDTH);
                    height = integer(format, MediaFormat.KEY_HEIGHT);
                    frameRate = integer(format, MediaFormat.KEY_FRAME_RATE);
                } else if (mime.startsWith("audio/") && audioCodec == null) {
                    audioCodec = codecName(mime);
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION));
                }
            }
            if (width > 0 && height > 0) {
                appendInfo(details, "Resolution", width + " × " + height);
            }
            if (frameRate > 0) {
                appendInfo(details, "Frame rate", frameRate + " fps");
            }
            appendInfo(details, "Video codec", videoCodec);
            appendInfo(details, "Audio codec", audioCodec);
            if (durationUs > 0) {
                appendInfo(details, "Duration", formatDuration(durationUs / 1_000_000L));
            }
        } catch (Exception ignored) {
            appendInfo(details, "Media details", "Unavailable");
        } finally {
            extractor.release();
        }
        return details.toString();
    }

    private String basicInfo(CobaltDownloadRepository.Record record) {
        StringBuilder details = new StringBuilder();
        appendInfo(details, "Status", statusText(record));
        appendInfo(details, "File", record.filename);
        appendInfo(details, "Container", containerName(record.filename));
        if (record.totalBytes > 0) {
            appendInfo(details, "Size", formatBytes(record.totalBytes));
        } else if (record.receivedBytes > 0) {
            appendInfo(details, "Downloaded", formatBytes(record.receivedBytes));
        }
        appendInfo(details, "Date", DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT
        ).format(new Date(record.createdAt)));
        appendInfo(details, "Source", record.sourceUrl);
        return details.toString();
    }

    private void appendInfo(StringBuilder details, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (details.length() > 0) {
            details.append('\n');
        }
        details.append(label).append(": ").append(value);
    }

    private int integer(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private String codecName(String mime) {
        if ("video/av01".equalsIgnoreCase(mime)) return "AV1";
        if ("video/avc".equalsIgnoreCase(mime)) return "H.264";
        if ("video/hevc".equalsIgnoreCase(mime)) return "H.265 / HEVC";
        if ("video/x-vnd.on2.vp9".equalsIgnoreCase(mime)) return "VP9";
        if ("audio/mp4a-latm".equalsIgnoreCase(mime)) return "AAC";
        if ("audio/opus".equalsIgnoreCase(mime)) return "Opus";
        if ("audio/mpeg".equalsIgnoreCase(mime)) return "MP3";
        int slash = mime.indexOf('/');
        return slash >= 0 ? mime.substring(slash + 1).toUpperCase(Locale.US) : mime;
    }

    private String containerName(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot + 1 < filename.length()
                ? filename.substring(dot + 1).toUpperCase(Locale.US)
                : null;
    }

    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.US, "%d:%02d", minutes, remainder);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160)
        );
        params.bottomMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int themeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            try {
                return getResources().getColor(value.resourceId, getTheme());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data
                : fallback;
    }

    private int namedColor(String name, int fallback) {
        int identifier = getResources().getIdentifier(name, "color", getPackageName());
        if (identifier == 0) {
            identifier = getResources().getIdentifier(name, "color", ORIGINAL_PACKAGE);
        }
        if (identifier == 0) {
            return fallback;
        }
        try {
            return getResources().getColor(identifier, getTheme());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Drawable themeDrawable(int attribute) {
        TypedArray attributes = getTheme().obtainStyledAttributes(new int[]{attribute});
        try {
            Drawable drawable = attributes.getDrawable(0);
            return drawable == null ? null : drawable.mutate();
        } finally {
            attributes.recycle();
        }
    }

    private Boolean morpheDarkMode() {
        try {
            Class<?> utils = Class.forName("app.morphe.extension.shared.Utils");
            Object value = utils.getMethod("isDarkModeEnabled").invoke(null);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDark(int color) {
        double luminance = 0.2126 * Color.red(color)
                + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color);
        return luminance < 128;
    }

    private int blend(int base, int overlay, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(base) * inverse + Color.red(overlay) * amount),
                Math.round(Color.green(base) * inverse + Color.green(overlay) * amount),
                Math.round(Color.blue(base) * inverse + Color.blue(overlay) * amount)
        );
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
