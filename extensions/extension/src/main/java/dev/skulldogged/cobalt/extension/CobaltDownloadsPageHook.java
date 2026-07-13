package dev.skulldogged.cobalt.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.WeakHashMap;

final class CobaltDownloadsPageHook implements Application.ActivityLifecycleCallbacks {
    private static final String DOWNLOADS_ENTRY_ID = "downloads_page_entry_point_container";

    private final Application application;
    private final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            new WeakHashMap<>();

    private CobaltDownloadsPageHook(Application application) {
        this.application = application;
    }

    static void install(Object value) {
        if (value instanceof Application) {
            Application application = (Application) value;
            application.registerActivityLifecycleCallbacks(
                    new CobaltDownloadsPageHook(application)
            );
        }
    }

    static void open(Activity activity) {
        activity.startActivity(new Intent(activity, CobaltDownloadsActivity.class));
    }

    private void attach(Activity activity) {
        if (activity instanceof CobaltDownloadsActivity || listeners.containsKey(activity)) {
            return;
        }
        View root = activity.getWindow().getDecorView();
        int entryId = application.getResources().getIdentifier(
                DOWNLOADS_ENTRY_ID,
                "id",
                application.getPackageName()
        );
        if (entryId == 0) {
            return;
        }
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            View entry = root.findViewById(entryId);
            if (entry != null) {
                entry.setOnClickListener(ignored -> open(activity));
                entry.setClickable(true);
            }
        };
        listeners.put(activity, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        listener.onGlobalLayout();
    }

    private void detach(Activity activity) {
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(activity);
        if (listener == null) {
            return;
        }
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(listener);
        }
    }

    @Override public void onActivityResumed(Activity activity) { attach(activity); }
    @Override public void onActivityPaused(Activity activity) { detach(activity); }
    @Override public void onActivityDestroyed(Activity activity) { detach(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
