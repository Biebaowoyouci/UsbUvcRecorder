package com.codex.uvcrecorder;

import com.pedro.encoder.Frame;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.input.sources.audio.AudioSource;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Feeds the selected UAC/HDMI embedded PCM stream into the RTMP AAC encoder. */
final class UacRtmpAudioSource extends AudioSource {
    // RootEncoder's AAC input buffer is 8192 bytes. Supplying a larger Frame
    // silently truncates its tail, which sounds like repeated crackles.
    static final int AAC_INPUT_BYTES = 8192;
    static final long HARD_RESYNC_US = 150_000L;
    static final long MAX_SLEW_US = 2_000L;

    private final PcmAudioSubscription subscription;
    private volatile boolean running;
    private Thread pumpThread;

    UacRtmpAudioSource(PcmAudioSubscription subscription) {
        this.subscription = subscription;
    }

    @Override
    protected boolean create(int sampleRate, boolean isStereo, boolean echoCanceler,
                             boolean noiseSuppressor) {
        int requestedChannels = isStereo ? 2 : 1;
        return sampleRate == subscription.sampleRate
                && requestedChannels == subscription.channelCount
                && subscription.bytesPerSample == 2;
    }

    @Override
    public void start(GetMicrophoneData callback) {
        if (running) return;
        running = true;
        pumpThread = new Thread(() -> pump(callback), "rtmp-uac-audio");
        pumpThread.start();
    }

    private void pump(GetMicrophoneData callback) {
        final int frameBytes = subscription.channelCount * subscription.bytesPerSample;
        final int maxChunkBytes = alignedChunkBytes(frameBytes);
        long nextTimestampUs = 0;
        try {
            while (running) {
                if (subscription.failure != null) {
                    throw new IllegalStateException("RTMP UAC 音频已中断",
                            subscription.failure);
                }
                byte[] pcm = subscription.queue.poll(250, TimeUnit.MILLISECONDS);
                if (pcm == null || pcm.length == 0) continue;
                int alignedLength = pcm.length - pcm.length % frameBytes;
                long captureStartUs = subscription.estimateCaptureStartUs(alignedLength);
                int offset = 0;
                while (running && offset < alignedLength) {
                    int length = Math.min(maxChunkBytes, alignedLength - offset);
                    length -= length % frameBytes;
                    if (length <= 0) break;
                    byte[] chunk = offset == 0 && length == pcm.length
                            ? pcm : Arrays.copyOfRange(pcm, offset, offset + length);
                    long durationUs = pcmDurationUs(length, subscription.sampleRate,
                            frameBytes);
                    long captureTimestampUs = captureStartUs
                            + pcmDurationUs(offset, subscription.sampleRate, frameBytes);
                    long submittedTimestampUs = synchronizeTimestampUs(nextTimestampUs,
                            captureTimestampUs, durationUs);
                    callback.inputPCMData(new Frame(chunk, 0, length, submittedTimestampUs));
                    nextTimestampUs = submittedTimestampUs + durationUs;
                    offset += length;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    static int alignedChunkBytes(int frameBytes) {
        int safeFrameBytes = Math.max(1, frameBytes);
        return AAC_INPUT_BYTES - AAC_INPUT_BYTES % safeFrameBytes;
    }

    static long pcmDurationUs(int bytes, int sampleRate, int frameBytes) {
        if (bytes <= 0 || sampleRate <= 0 || frameBytes <= 0) return 0;
        return (bytes / frameBytes) * 1_000_000L / sampleRate;
    }

    /**
     * Keep AAC timestamps on the same monotonic clock as the video encoder.
     * A forward jump means PCM was dropped upstream, so re-anchor immediately.
     * Small capture-clock differences are corrected gradually and timestamps
     * are never allowed to move backwards.
     */
    static long synchronizeTimestampUs(long nextTimestampUs, long captureTimestampUs,
                                       long frameDurationUs) {
        if (nextTimestampUs <= 0) return Math.max(0, captureTimestampUs);
        long driftUs = captureTimestampUs - nextTimestampUs;
        if (driftUs >= HARD_RESYNC_US) return captureTimestampUs;
        if (driftUs <= -HARD_RESYNC_US) return nextTimestampUs;
        long maxSlewUs = Math.min(MAX_SLEW_US, Math.max(1, frameDurationUs / 8));
        long correctionUs = driftUs / 8;
        correctionUs = Math.max(-maxSlewUs, Math.min(maxSlewUs, correctionUs));
        long candidate = nextTimestampUs + correctionUs;
        long previousTimestampUs = nextTimestampUs - Math.max(1, frameDurationUs);
        return Math.max(previousTimestampUs + 1, candidate);
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = pumpThread;
        pumpThread = null;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(800);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        subscription.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void release() {
        stop();
    }
}
