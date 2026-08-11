package com.codex.uvcrecorder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class AudioLevelMonitor {
    interface Listener {
        void onLevel(float normalized, float db);

        void onUnavailable();
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "uvc-audio-level"));
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean released;

    AudioLevelMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start(UvcSurfaceSource source) {
        int request = generation.incrementAndGet();
        mainHandler.post(listener::onUnavailable);
        worker.execute(() -> monitor(request, source));
    }

    void stop() {
        generation.incrementAndGet();
        mainHandler.post(listener::onUnavailable);
    }

    void release() {
        released = true;
        generation.incrementAndGet();
        worker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void monitor(int request, UvcSurfaceSource source) {
        if (released || request != generation.get() || source == null) return;
        PcmAudioSubscription subscription = null;
        try {
            subscription = source.subscribeAudio(context);
            while (!released && request == generation.get()) {
                byte[] pcm = subscription.queue.poll(120, TimeUnit.MILLISECONDS);
                if (subscription.failure != null) throw subscription.failure;
                if (pcm == null) continue;
                byte[] newer;
                while ((newer = subscription.queue.poll()) != null) pcm = newer;
                long squares = 0;
                int samples = 0;
                for (int index = 0; index + 1 < pcm.length; index += 2) {
                    int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
                    squares += (long) sample * sample;
                    samples++;
                }
                if (samples == 0) continue;
                double rms = Math.sqrt(squares / (double) samples) / 32767.0;
                float db = (float) (20.0 * Math.log10(Math.max(rms, 1.0 / 32767.0)));
                float normalized = Math.max(0f, Math.min(1f, (db + 60f) / 60f));
                mainHandler.post(() -> {
                    if (!released && request == generation.get()) {
                        listener.onLevel(normalized, db);
                    }
                });
            }
        } catch (Throwable ignored) {
            mainHandler.post(() -> {
                if (!released && request == generation.get()) listener.onUnavailable();
            });
        } finally {
            if (subscription != null) subscription.close();
        }
    }
}
