package com.codex.uvcrecorder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures the USB audio input selected in settings and fans that PCM out to every
 * per-device recorder. The output is always 48 kHz stereo.
 */
final class MultiAudioRouter {
    private static final String TAG = "MultiAudioRouter";
    private static final int OUTPUT_CHANNELS = 2;
    private static final int OUTPUT_BUFFER_SIZE = 38_400;

    interface Listener {
        void onLevel(float normalized, float db, String sourceLabel);

        void onUnavailable();
    }

    static final class Source {
        final String key;
        final String label;
        final String productName;
        final int deviceId;

        Source(String key, String label, String productName, int deviceId) {
            this.key = key;
            this.label = label;
            this.productName = productName;
            this.deviceId = deviceId;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "multi-uac-manual-router");
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final Object outputLock = new Object();
    private final List<PcmAudioSubscription> outputs = new ArrayList<>();
    private volatile boolean released;
    private volatile boolean inputAvailable;
    private volatile String sourceSignature = "";
    private volatile String selectedSourceLabel = "USB UAC";

    MultiAudioRouter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void setSources(List<Source> sources) {
        List<Source> safe = sources == null
                ? Collections.emptyList() : new ArrayList<>(sources);
        StringBuilder signature = new StringBuilder();
        for (Source source : safe) signature.append(source.key).append('|');
        String nextSignature = signature.toString();
        if (nextSignature.equals(sourceSignature)) return;
        sourceSignature = nextSignature;
        selectedSourceLabel = safe.isEmpty() ? "USB UAC" : safe.get(0).label;
        inputAvailable = false;
        synchronized (outputLock) {
            outputLock.notifyAll();
        }
        int request = generation.incrementAndGet();
        mainHandler.post(listener::onUnavailable);
        worker.execute(() -> monitor(request, safe));
    }

    PcmAudioSubscription subscribe() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 1_800;
        synchronized (outputLock) {
            while (!released && !inputAvailable
                    && SystemClock.elapsedRealtime() < deadline) {
                outputLock.wait(Math.min(120,
                        Math.max(1, deadline - SystemClock.elapsedRealtime())));
            }
            if (released) throw new IllegalStateException("多设备音频共享已关闭");
            if (!inputAvailable) {
                throw new IllegalStateException("未检测到可共享的 UAC/HDMI 嵌入音频");
            }
            PcmAudioSubscription result = new PcmAudioSubscription(
                    UsbAudioSource.SAMPLE_RATE, OUTPUT_CHANNELS,
                    UsbAudioSource.BYTES_PER_SAMPLE, OUTPUT_BUFFER_SIZE,
                    "手动共享：" + selectedSourceLabel, -1,
                    "已使用设置中手动选择的共享音源",
                    this::unsubscribe);
            outputs.add(result);
            return result;
        }
    }

    void release() {
        if (released) return;
        released = true;
        generation.incrementAndGet();
        sourceSignature = "";
        inputAvailable = false;
        synchronized (outputLock) {
            for (PcmAudioSubscription output : new ArrayList<>(outputs)) {
                output.failure = new IllegalStateException("多设备音频共享已关闭");
            }
            outputs.clear();
            outputLock.notifyAll();
        }
        worker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void monitor(int request, List<Source> sources) {
        List<Input> inputs = new ArrayList<>();
        try {
            for (Source source : sources) {
                if (released || request != generation.get()) return;
                try {
                    PcmAudioSubscription subscription = UsbAudioHub.subscribeExact(
                            context, source.productName, source.deviceId);
                    // Keep only one probe for a physical routed device.
                    boolean duplicate = false;
                    for (Input existing : inputs) {
                        if ((subscription.deviceId >= 0
                                && existing.subscription.deviceId == subscription.deviceId)
                                || (subscription.deviceId < 0
                                && existing.subscription.deviceName.equals(
                                subscription.deviceName))) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) {
                        subscription.close();
                    } else {
                        inputs.add(new Input(subscription.deviceName, subscription));
                        Log.i(TAG, "monitoring " + source.label + " via "
                                + subscription.deviceName);
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "cannot monitor " + source.label, error);
                }
            }
            if (inputs.isEmpty()) {
                setInputAvailable(request, false);
                return;
            }
            setInputAvailable(request, true);
            route(request, inputs);
        } finally {
            for (Input input : inputs) input.subscription.close();
            if (request == generation.get()) setInputAvailable(request, false);
        }
    }

    private void route(int request, List<Input> inputs) {
        Input selected = inputs.get(0);
        long lastUiAt = 0;
        while (!released && request == generation.get()) {
            boolean received = false;
            byte[] pcm;
            int drained = 0;
            while (drained < 12 && (pcm = selected.subscription.queue.poll()) != null) {
                drained++;
                received = true;
                selected.updateLevel(pcm);
                fanOut(toStereo(pcm, selected.subscription.channelCount));
            }
            if (selected.subscription.failure != null) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastUiAt >= 80) {
                lastUiAt = now;
                float db = selected.smoothedDb;
                float normalized = Math.max(0f, Math.min(1f, (db + 60f) / 60f));
                String label = selectedSourceLabel;
                mainHandler.post(() -> {
                    if (!released && request == generation.get()) {
                        listener.onLevel(normalized, db, label);
                    }
                });
            }

            if (!received) {
                try {
                    Thread.sleep(12);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void setInputAvailable(int request, boolean available) {
        if (released || request != generation.get()) return;
        inputAvailable = available;
        synchronized (outputLock) {
            outputLock.notifyAll();
        }
        if (!available) mainHandler.post(listener::onUnavailable);
    }

    private void fanOut(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (outputLock) {
            for (PcmAudioSubscription output : outputs) output.offer(pcm);
        }
    }

    private void unsubscribe(PcmAudioSubscription subscription) {
        synchronized (outputLock) {
            outputs.remove(subscription);
        }
    }

    private static byte[] toStereo(byte[] pcm, int inputChannels) {
        if (inputChannels == OUTPUT_CHANNELS) return Arrays.copyOf(pcm, pcm.length);
        if (inputChannels <= 1) {
            int samples = pcm.length / 2;
            byte[] stereo = new byte[samples * 4];
            for (int sample = 0; sample < samples; sample++) {
                byte low = pcm[sample * 2];
                byte high = pcm[sample * 2 + 1];
                int target = sample * 4;
                stereo[target] = low;
                stereo[target + 1] = high;
                stereo[target + 2] = low;
                stereo[target + 3] = high;
            }
            return stereo;
        }
        int frames = pcm.length / (inputChannels * 2);
        byte[] stereo = new byte[frames * 4];
        for (int frame = 0; frame < frames; frame++) {
            int source = frame * inputChannels * 2;
            int target = frame * 4;
            stereo[target] = pcm[source];
            stereo[target + 1] = pcm[source + 1];
            stereo[target + 2] = pcm[source + 2];
            stereo[target + 3] = pcm[source + 3];
        }
        return stereo;
    }

    private static final class Input {
        final String label;
        final PcmAudioSubscription subscription;
        float smoothedDb = -90f;
        boolean failed;

        Input(String label, PcmAudioSubscription subscription) {
            this.label = label;
            this.subscription = subscription;
        }

        void updateLevel(byte[] pcm) {
            if (pcm == null || pcm.length < 2) return;
            double squares = 0;
            int samples = 0;
            for (int index = 0; index + 1 < pcm.length; index += 2) {
                int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
                squares += (double) sample * sample;
                samples++;
            }
            if (samples == 0) return;
            double rms = Math.sqrt(squares / samples) / 32767.0;
            float db = (float) (20.0 * Math.log10(Math.max(rms, 1.0 / 32767.0)));
            smoothedDb = smoothedDb <= -89f ? db : smoothedDb * 0.72f + db * 0.28f;
        }
    }
}
