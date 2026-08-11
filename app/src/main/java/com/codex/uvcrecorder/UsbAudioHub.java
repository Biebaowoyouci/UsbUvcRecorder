package com.codex.uvcrecorder;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Captures one UAC stream and fans the PCM out to the main and auxiliary encoders. */
final class UsbAudioHub {
    private static final String TAG = "UsbAudioHub";
    private static final Object LOCK = new Object();
    private static final Map<String, Session> SESSIONS = new HashMap<>();

    private UsbAudioHub() {
    }

    static PcmAudioSubscription subscribe(Context context) throws Exception {
        return subscribe(context, null, -1, true);
    }

    static PcmAudioSubscription subscribe(Context context, String preferredProduct) throws Exception {
        return subscribe(context, preferredProduct, -1, true);
    }

    static PcmAudioSubscription subscribeStrict(Context context,
                                                String preferredProduct) throws Exception {
        return subscribe(context, preferredProduct, -1, false);
    }

    static PcmAudioSubscription subscribeExact(Context context, String preferredProduct,
                                               int preferredDeviceId) throws Exception {
        return subscribe(context, preferredProduct, preferredDeviceId, false);
    }

    private static PcmAudioSubscription subscribe(Context context, String preferredProduct,
                                                  int preferredDeviceId,
                                                  boolean allowOtherDeviceFallback)
            throws Exception {
        synchronized (LOCK) {
            String productKey = preferredProduct == null || preferredProduct.trim().isEmpty()
                    ? "_default" : preferredProduct.trim().toLowerCase(Locale.US);
            String key = (preferredDeviceId >= 0 ? preferredDeviceId + ":" : "")
                    + productKey;
            Session activeSession = SESSIONS.get(key);
            String fallbackWarning = null;
            if (activeSession == null) {
                UsbAudioSource source = null;
                try {
                    source = UsbAudioSource.open(context, preferredProduct, preferredDeviceId);
                    if (!allowOtherDeviceFallback && source.warning != null) {
                        String warning = source.warning;
                        source.release();
                        source = null;
                        throw new IllegalStateException(warning
                                + "；共享关闭时不会借用其他设备音频");
                    }
                    Session shared = findByDeviceId(source.deviceId);
                    if (shared != null) {
                        fallbackWarning = combineWarnings(source.warning,
                                "同一 UAC 已在采集，已共享 " + shared.source.deviceName + " 音频");
                        source.release();
                        source = null;
                        activeSession = shared;
                        Log.i(TAG, "shared " + key + " from " + shared.key);
                    } else {
                        activeSession = new Session(key, source);
                        activeSession.start();
                        SESSIONS.put(key, activeSession);
                        fallbackWarning = source.warning;
                        Log.i(TAG, "started " + key + " on " + source.deviceName);
                    }
                } catch (Exception error) {
                    if (source != null) source.release();
                    if (!allowOtherDeviceFallback || SESSIONS.isEmpty()) throw error;
                    activeSession = SESSIONS.values().iterator().next();
                    fallbackWarning = combineWarnings(activeSession.source.warning,
                            "对应 UAC 无法打开，已共享 "
                                    + activeSession.source.deviceName + " 音频");
                    Log.w(TAG, fallbackWarning, error);
                }
            }
            Session session = activeSession;
            PcmAudioSubscription subscription = new PcmAudioSubscription(
                    UsbAudioSource.SAMPLE_RATE, session.source.channelCount,
                    UsbAudioSource.BYTES_PER_SAMPLE, session.source.bufferSize,
                    session.source.deviceName, session.source.deviceId, fallbackWarning,
                    item -> unsubscribe(session, item));
            activeSession.subscriptions.add(subscription);
            return subscription;
        }
    }

    private static Session findByDeviceId(int deviceId) {
        for (Session session : SESSIONS.values()) {
            if (session.source.deviceId == deviceId) return session;
        }
        return null;
    }

    private static String combineWarnings(String first, String second) {
        if (first == null || first.trim().isEmpty()) return second;
        if (second == null || second.trim().isEmpty()) return first;
        return first + "；" + second;
    }

    private static void unsubscribe(Session session, PcmAudioSubscription subscription) {
        Session toStop = null;
        synchronized (LOCK) {
            session.subscriptions.remove(subscription);
            if (session.subscriptions.isEmpty()) {
                if (SESSIONS.get(session.key) == session) SESSIONS.remove(session.key);
                toStop = session;
            }
        }
        if (toStop != null) toStop.stop();
    }

    private static final class Session {
        final String key;
        final UsbAudioSource source;
        final List<PcmAudioSubscription> subscriptions = new ArrayList<>();
        volatile boolean running = true;
        Thread thread;

        Session(String key, UsbAudioSource source) {
            this.key = key;
            this.source = source;
        }

        void start() {
            thread = new Thread(this::capture, "usb-uac-shared-capture");
            thread.start();
        }

        void capture() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] buffer = new byte[Math.min(Math.max(4096, source.bufferSize / 4), 32 * 1024)];
            Throwable failure = null;
            long capturedBytes = 0;
            int peak = 0;
            long nextStatsAt = android.os.SystemClock.elapsedRealtime() + 1_000;
            try {
                while (running) {
                    int read = source.record.read(buffer, 0, buffer.length,
                            android.media.AudioRecord.READ_BLOCKING);
                    if (read > 0) {
                        capturedBytes += read;
                        for (int i = 0; i + 1 < read; i += 2) {
                            int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                            peak = Math.max(peak, Math.min(32767, Math.abs(sample)));
                        }
                        byte[] sample = java.util.Arrays.copyOf(buffer, read);
                        synchronized (LOCK) {
                            for (PcmAudioSubscription subscription : subscriptions) {
                                subscription.offer(sample);
                            }
                        }
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now >= nextStatsAt) {
                            Log.i(TAG, "PCM " + source.deviceName + " bytes=" + capturedBytes
                                    + " peak=" + peak + "/32767");
                            capturedBytes = 0;
                            peak = 0;
                            nextStatsAt = now + 5_000;
                        }
                    } else if (read < 0 && running) {
                        throw new IllegalStateException("UAC 音频读取失败：" + read);
                    }
                }
            } catch (Throwable error) {
                if (running) failure = error;
            } finally {
                running = false;
                source.release();
                synchronized (LOCK) {
                    if (SESSIONS.get(key) == this) SESSIONS.remove(key);
                    if (failure != null) {
                        for (PcmAudioSubscription subscription : subscriptions) {
                            subscription.failure = failure;
                        }
                    }
                }
            }
        }

        void stop() {
            running = false;
            source.stop();
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
