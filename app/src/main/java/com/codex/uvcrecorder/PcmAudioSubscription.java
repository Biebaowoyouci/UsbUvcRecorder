package com.codex.uvcrecorder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final CloseCallback closeCallback;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    volatile Throwable failure;

    PcmAudioSubscription(int sampleRate, int channelCount, int bytesPerSample,
                         int bufferSize, String deviceName, int deviceId, String warning,
                         CloseCallback closeCallback) {
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.bytesPerSample = bytesPerSample;
        this.bufferSize = bufferSize;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
        this.warning = warning;
        this.closeCallback = closeCallback;
    }

    void offer(byte[] pcm) {
        if (closed.get()) return;
        if (!queue.offer(pcm)) {
            queue.poll();
            queue.offer(pcm);
        }
    }

    void close() {
        if (closed.compareAndSet(false, true)) closeCallback.close(this);
    }
}
