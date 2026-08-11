package com.codex.uvcrecorder;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

import com.serenegiant.utils.UVCUtils;

public final class UsbRecorderApplication extends Application {
    private static final AtomicInteger STARTED_ACTIVITIES = new AtomicInteger();

    @Override
    public void onCreate() {
        super.onCreate();
        UVCUtils.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }

            @Override
            public void onActivityStarted(Activity activity) {
                STARTED_ACTIVITIES.incrementAndGet();
            }

            @Override
            public void onActivityStopped(Activity activity) {
                STARTED_ACTIVITIES.updateAndGet(value -> Math.max(0, value - 1));
            }
        });
    }

    static boolean isAppInForeground() {
        return STARTED_ACTIVITIES.get() > 0;
    }
}
