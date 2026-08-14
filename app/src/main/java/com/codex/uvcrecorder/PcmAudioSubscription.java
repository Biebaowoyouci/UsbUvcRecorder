package com.codex.uvcrecorder;

import android.os.SystemClock;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.WeakHashMap;

/** A shared PCM feed consumed by one recording channel. */
final class PcmAudioSubscription {
    interface CloseCallback {
        void close(PcmAudioSubscription subscription);
    }

    final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(48);
    final int sampleRate;
    final int channelCount;
    final int bytesPerSample;
    final int bufferSize;
    final String deviceName;
    final int deviceId;
    final String warning;
    final boolean preciseSourceTimestamps;
    private final CloseCallback closeCallback;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<byte[], Long> sourcePresentationTimesUs = new WeakHashMap<>();
    private volatile long latestCaptureEndUs;
    volatile Throwable failure;

    PcmAudioSubscription(int sampleRate, int channelCount, int bytesPerSample,
                         int bufferSize, String deviceName, int deviceId, String warning,
                         CloseCallback closeCallback) {
        this(sampleRate, channelCount, bytesPerSample, bufferSize, deviceName,
                deviceId, warning, false, closeCallback);
    }

    PcmAudioSubscription(int sampleRate, int channelCount, int bytesPerSample,
                         int bufferSize, String deviceName, int deviceId, String warning,
                         boolean preciseSourceTimestamps, CloseCallback closeCallback) {
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.bytesPerSample = bytesPerSample;
        this.bufferSize = bufferSize;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
        this.warning = warning;
        this.preciseSourceTimestamps = preciseSourceTimestamps;
        this.closeCallback = closeCallback;
    }

    void offer(byte[] pcm) {
        long endUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
        long frameBytes = (long) channelCount * bytesPerSample;
        long durationUs = frameBytes <= 0 || sampleRate <= 0
                ? 0 : (pcm.length / frameBytes) * 1_000_000L / sampleRate;
        offer(pcm, Math.max(0, endUs - durationUs));
    }

    /**
     * Adds PCM together with the source media timestamp of its first sample.
     * Network playback uses the decoded stream PTS; live capture uses the
     * monotonic capture clock. Recording can therefore keep both tracks on
     * one clock even when a queue block is dropped.
     */
    void offer(byte[] pcm, long sourcePresentationTimeUs) {
        if (closed.get()) return;
        synchronized (sourcePresentationTimesUs) {
            sourcePresentationTimesUs.put(pcm, sourcePresentationTimeUs);
        }
        if (!queue.offer(pcm)) {
            byte[] dropped = queue.poll();
            if (dropped != null) {
                synchronized (sourcePresentationTimesUs) {
                    sourcePresentationTimesUs.remove(dropped);
                }
            }
            queue.offer(pcm);
        }
        // AudioRecord.read() returns a complete PCM block. The fan-out occurs
        // immediately afterwards, so this monotonic time is a close estimate
        // of the end of that block. RTMP uses it to recover from queue drops
        // without allowing the audio timeline to drift behind video forever.
        latestCaptureEndUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
    }

    long takeSourcePresentationTimeUs(byte[] pcm) {
        if (pcm == null) return Long.MIN_VALUE;
        synchronized (sourcePresentationTimesUs) {
            Long timestamp = sourcePresentationTimesUs.remove(pcm);
            return timestamp == null ? Long.MIN_VALUE : timestamp;
        }
    }

    void clearPending() {
        queue.clear();
        synchronized (sourcePresentationTimesUs) {
            sourcePresentationTimesUs.clear();
        }
    }

    long estimateCaptureStartUs(int currentBytes) {
        long queuedBytes = Math.max(0, currentBytes);
        for (byte[] pending : queue) {
            if (pending != null) queuedBytes += pending.length;
        }
        long endUs = latestCaptureEndUs;
        if (endUs <= 0) endUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
        long frameBytes = (long) channelCount * bytesPerSample;
        long durationUs = frameBytes <= 0 || sampleRate <= 0
                ? 0 : (queuedBytes / frameBytes) * 1_000_000L / sampleRate;
        return Math.max(0, endUs - durationUs);
    }

    void close() {
        if (closed.compareAndSet(false, true)) closeCallback.close(this);
    }
}
