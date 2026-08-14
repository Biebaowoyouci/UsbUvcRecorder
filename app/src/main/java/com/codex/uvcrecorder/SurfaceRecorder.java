package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class SurfaceRecorder {
    private static final String TAG = "SurfaceRecorder";
    interface Callback {
        void onFinished(SurfaceRecorder recorder, RecordingTarget target, Throwable error);
    }

    private static final int AUDIO_BIT_RATE = 192_000;
    private static final int NO_VIDEO_TIMEOUT_MS = 4_000;
    private static final int STOP_TIMEOUT_MS = 30_000;
    private static final long CLOCK_DOMAIN_TOLERANCE_US = 30_000_000L;
    private static final long SOURCE_TIMESTAMP_RESET_TOLERANCE_US = 250_000L;
    private static final long VIDEO_STALL_GAP_TOLERANCE_US = 250_000L;
    private static final long PRECISE_AUDIO_JITTER_TOLERANCE_US = 10_000L;
    private static final long CAPTURE_AUDIO_JITTER_TOLERANCE_US = 150_000L;
    private static final long AUDIO_ENCODER_PRIMING_TOLERANCE_US = 1_000_000L;

    private final Context context = UsbRecorderApplicationHolder.context();
    private final UvcSurfaceSource cameraSource;
    private final RecordingTarget target;
    private final int width;
    private final int height;
    private final int fps;
    private final int bitRate;
    private final AppSettings.VideoCodec codec;
    private final boolean requestUsbAudio;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean audioCaptureEnded = new AtomicBoolean(true);
    private final List<PendingSample> pendingSamples = new ArrayList<>();

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private Surface inputSurface;
    private RecordingMuxer muxer;
    private HandlerThread drainThread;
    private PcmAudioSubscription audioSubscription;
    private boolean audioActive;
    private boolean surfaceAttached;
    private boolean muxerStarted;
    private int expectedTracks = 1;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private volatile int videoSampleCount;
    private volatile int audioSampleCount;
    private long timelineOriginUs = Long.MIN_VALUE;
    private long videoPtsAdjustmentUs = Long.MIN_VALUE;
    private long audioPtsAdjustmentUs = Long.MIN_VALUE;
    private long lastVideoMuxPtsUs = -1;
    private long lastAudioMuxPtsUs = -1;
    private long lastRawVideoPtsUs = Long.MIN_VALUE;
    private long lastRawAudioPtsUs = Long.MIN_VALUE;
    private int videoTimelineGeneration;
    private int audioTimelineGeneration;
    private int correctedTimelineGeneration = Integer.MIN_VALUE;
    private long correctedTimelineAdjustmentUs = Long.MIN_VALUE;
    private long audioSourcePtsAdjustmentUs = Long.MIN_VALUE;
    private long lastRawAudioSourcePtsUs = Long.MIN_VALUE;
    private int audioInputTimelineGeneration;
    private long lastAudioInputEndPtsUs = Long.MIN_VALUE;
    private long firstAudioInputPtsUs = Long.MIN_VALUE;
    private long submittedAudioBytes;
    private long insertedSilenceBytes;
    private long droppedOverlapBytes;
    private volatile Throwable asynchronousFailure;
    private volatile long stopRequestedAtMs;
    private String startWarning;
    private byte[] currentPcm;
    private int currentPcmOffset;
    private long currentPcmTimelineStartUs = Long.MIN_VALUE;
    private boolean audioInputEos;

    SurfaceRecorder(UvcSurfaceSource cameraSource, RecordingTarget target, int width, int height,
                    int fps, int bitRate, AppSettings.VideoCodec codec,
                    boolean requestUsbAudio, Callback callback) {
        this.cameraSource = cameraSource;
        this.target = target;
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.bitRate = bitRate;
        this.codec = codec;
        this.requestUsbAudio = requestUsbAudio;
        this.callback = callback;
    }

    void start() throws Exception {
        try {
            String videoMime = codec == AppSettings.VideoCodec.H265
                    ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            MediaFormat videoFormat = MediaFormat.createVideoFormat(videoMime, width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            videoEncoder = MediaCodec.createEncoderByType(videoMime);
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();

            prepareAudioIfAvailable();
            muxer = target.container == AppSettings.Container.AVI
                    ? new AviH264Muxer(target.descriptor.getFileDescriptor(), videoMime)
                    : new Mp4RecordingMuxer(target.descriptor.getFileDescriptor());

            videoEncoder.start();
            if (audioActive) {
                audioEncoder.start();
                // The subscription starts before the video encoder surface is
                // attached. Discard that pre-roll so audio cannot lead video.
                audioSubscription.clearPending();
            }
            long videoTimelineUs = cameraSource.getVideoTimelinePositionUs();
            timelineOriginUs = videoTimelineUs != Long.MIN_VALUE
                    ? videoTimelineUs
                    : SystemClock.elapsedRealtimeNanos() / 1_000L;
            videoTimelineGeneration = cameraSource.getTimelineGeneration();
            audioTimelineGeneration = videoTimelineGeneration;
            audioInputTimelineGeneration = videoTimelineGeneration;
            cameraSource.addRecordingSurface(inputSurface, width, height);
            surfaceAttached = true;

            drainThread = new HandlerThread("uvc-recorder-" + target.displayName);
            drainThread.start();
            new Handler(drainThread.getLooper()).post(this::drainEncoders);
            mainHandler.postDelayed(this::abortIfNoVideo, NO_VIDEO_TIMEOUT_MS);
        } catch (Exception error) {
            cleanupCodecs();
            throw error;
        }
    }

    void stop() {
        requestStop(null);
    }

    String getDisplayName() {
        return target.displayName;
    }

    String getStartWarning() {
        return startWarning;
    }

    private void prepareAudioIfAvailable() {
        if (!requestUsbAudio) return;
        if (target.container == AppSettings.Container.AVI) {
            startWarning = "AVI 当前只录视频；如需 UAC/HDMI 音频请使用 MP4、MOV 或 M4V";
            return;
        }
        if (cameraSource.requiresRecordAudioPermission()
                && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startWarning = "未授予录音权限，本段只录制视频";
            return;
        }
        try {
            audioSubscription = cameraSource.subscribeAudio(context);
            if (audioSubscription.warning != null) {
                startWarning = audioSubscription.warning;
            }
            MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    audioSubscription.sampleRate, audioSubscription.channelCount);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE,
                    audioSubscription.channelCount == 2 ? AUDIO_BIT_RATE : 128_000);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioSubscription.bufferSize);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioActive = true;
            audioCaptureEnded.set(false);
            expectedTracks = 2;
            Log.i(TAG, "audio prepared file=" + target.displayName + " device="
                    + audioSubscription.deviceName + " rate=" + audioSubscription.sampleRate
                    + " channels=" + audioSubscription.channelCount);
        } catch (Exception error) {
            startWarning = error.getMessage() == null ? "USB UAC 音频不可用，本段只录制视频"
                    : error.getMessage() + "；本段只录制视频";
            releaseAudioOnly();
        }
    }

    private void requestStop(Throwable failure) {
        if (failure != null && asynchronousFailure == null) asynchronousFailure = failure;
        if (!stopping.compareAndSet(false, true)) return;
        stopRequestedAtMs = SystemClock.elapsedRealtime();
        detachSurface();
        if (audioSubscription != null) {
            audioSubscription.close();
            // Network/UAC capture can have up to a few hundred milliseconds
            // of PCM queued ahead. A screen recorder stops at the button
            // timestamp, so do not append that not-yet-presented tail.
            audioSubscription.clearPending();
        }
        audioCaptureEnded.set(true);
        try {
            if (videoEncoder != null) videoEncoder.signalEndOfInputStream();
        } catch (Exception ignored) {
        }
    }

    private void abortIfNoVideo() {
        if (!stopping.get() && videoSampleCount == 0) {
            requestStop(new EmptyRecordingException(
                    "4 秒内没有收到视频帧，已删除空文件；请点“信号”选择 1080p30 MJPEG 或检查 HDMI 输出"));
        }
    }

    private void drainEncoders() {
        MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        boolean videoEos = false;
        boolean audioEos = !audioActive;
        Throwable failure = null;
        try {
            while (!videoEos || !audioEos) {
                boolean progressed = false;
                if (audioActive && !audioInputEos) {
                    progressed |= feedAudioInput(videoEos);
                }
                DrainResult videoResult = drainOne(videoEncoder, videoInfo, false);
                progressed |= videoResult.progressed;
                videoEos |= videoResult.eos;
                if (audioActive) {
                    DrainResult audioResult = drainOne(audioEncoder, audioInfo, true);
                    progressed |= audioResult.progressed;
                    audioEos |= audioResult.eos;
                }
                if (stopping.get() && stopRequestedAtMs > 0
                        && SystemClock.elapsedRealtime() - stopRequestedAtMs > STOP_TIMEOUT_MS) {
                    throw new IllegalStateException("编码器停止超时，录制文件未保存");
                }
                if (!progressed) SystemClock.sleep(2);
            }
            if (asynchronousFailure != null) throw asynchronousFailure;
            if (videoSampleCount == 0) {
                throw new EmptyRecordingException("没有编码到视频帧，已删除空文件");
            }
            if (muxerStarted) muxer.stop();
        } catch (Throwable error) {
            failure = error;
        } finally {
            Log.i(TAG, "recording finished file=" + target.displayName + " videoSamples="
                    + videoSampleCount + " audioSamples=" + audioSampleCount
                    + " pcmBytes=" + submittedAudioBytes
                    + " silenceBytes=" + insertedSilenceBytes
                    + " overlapDroppedBytes=" + droppedOverlapBytes
                    + " error=" + failure);
            mainHandler.removeCallbacksAndMessages(null);
            cleanupCodecs();
            Throwable finalFailure = failure;
            if (finalFailure == null) target.complete(context);
            else target.cancel(context);
            mainHandler.post(() -> callback.onFinished(this, target, finalFailure));
        }
    }

    private boolean feedAudioInput(boolean videoEos) {
        if (audioEncoder == null || audioInputEos) return false;
        if (audioSubscription.failure != null && !stopping.get()) {
            requestStop(audioSubscription.failure);
        }
        if (stopping.get() && audioCaptureEnded.get()) {
            currentPcm = null;
            currentPcmOffset = 0;
            currentPcmTimelineStartUs = Long.MIN_VALUE;
            audioSubscription.clearPending();
        }
        if (currentPcm == null) {
            currentPcm = audioSubscription.queue.poll();
            currentPcmOffset = 0;
            if (currentPcm != null) {
                long sourcePtsUs =
                        audioSubscription.takeSourcePresentationTimeUs(currentPcm);
                if (sourcePtsUs == Long.MIN_VALUE) {
                    sourcePtsUs =
                            audioSubscription.estimateCaptureStartUs(currentPcm.length);
                }
                currentPcmTimelineStartUs = normalizeAudioSourcePtsUs(sourcePtsUs);
            }
        }
        if (dropAudioOverlapIfNeeded()) return true;
        boolean sourceAudioEnded = stopping.get() && audioCaptureEnded.get()
                && currentPcm == null && audioSubscription.queue.isEmpty();
        boolean shouldEnd = sourceAudioEnded && videoEos;
        if (currentPcm == null && !shouldEnd) return false;

        int index = audioEncoder.dequeueInputBuffer(0);
        if (index < 0) return false;
        ByteBuffer input = audioEncoder.getInputBuffer(index);
        if (input == null) throw new IllegalStateException("AAC 编码器返回空输入缓冲区");
        input.clear();
        if (shouldEnd) {
            long audioEndPtsUs = lastAudioInputEndPtsUs == Long.MIN_VALUE
                    ? 0 : lastAudioInputEndPtsUs;
            long videoEndPtsUs = Math.max(0, lastVideoMuxPtsUs);
            long trailingGapUs = audioGapToFillUs(videoEndPtsUs, audioEndPtsUs,
                    audioJitterToleranceUs());
            if (trailingGapUs > 0) {
                int frameBytes = audioSubscription.channelCount
                        * audioSubscription.bytesPerSample;
                int silenceLength = Math.min(input.remaining(),
                        pcmBytesForDurationCeil(trailingGapUs));
                silenceLength -= silenceLength % frameBytes;
                if (silenceLength <= 0) {
                    throw new IllegalStateException("AAC 输入缓冲区无法补齐尾部静音");
                }
                putSilence(input, silenceLength);
                long durationUs = pcmDurationUs(silenceLength);
                audioEncoder.queueInputBuffer(index, 0, silenceLength,
                        audioEndPtsUs, 0);
                if (firstAudioInputPtsUs == Long.MIN_VALUE) {
                    firstAudioInputPtsUs = audioEndPtsUs;
                }
                lastAudioInputEndPtsUs = audioEndPtsUs + durationUs;
                submittedAudioBytes += silenceLength;
                insertedSilenceBytes += silenceLength;
                return true;
            }
            long pts = lastAudioInputEndPtsUs != Long.MIN_VALUE
                    ? lastAudioInputEndPtsUs : 0;
            audioEncoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            audioInputEos = true;
            return true;
        }

        int remaining = currentPcm.length - currentPcmOffset;
        int frameBytes = audioSubscription.channelCount * audioSubscription.bytesPerSample;
        long candidatePtsUs = currentPcmTimelineStartUs
                + pcmDurationUs(currentPcmOffset);
        long gapUs = audioGapToFillUs(candidatePtsUs, lastAudioInputEndPtsUs,
                audioJitterToleranceUs());
        if (gapUs > 0) {
            int silenceLength = Math.min(input.remaining(),
                    pcmBytesForDurationCeil(gapUs));
            silenceLength -= silenceLength % frameBytes;
            if (silenceLength <= 0) {
                throw new IllegalStateException("AAC 输入缓冲区无法容纳完整静音采样帧");
            }
            putSilence(input, silenceLength);
            long pts = lastAudioInputEndPtsUs;
            long durationUs = pcmDurationUs(silenceLength);
            audioEncoder.queueInputBuffer(index, 0, silenceLength, pts, 0);
            if (firstAudioInputPtsUs == Long.MIN_VALUE) firstAudioInputPtsUs = pts;
            lastAudioInputEndPtsUs = pts + durationUs;
            submittedAudioBytes += silenceLength;
            insertedSilenceBytes += silenceLength;
            return true;
        }

        int length = Math.min(input.remaining(), remaining);
        length -= length % frameBytes;
        if (length <= 0) throw new IllegalStateException("AAC 输入缓冲区过小");
        long durationUs = pcmDurationUs(length);
        long pts = monotonicAudioInputPtsUs(candidatePtsUs, lastAudioInputEndPtsUs);
        input.put(currentPcm, currentPcmOffset, length);
        audioEncoder.queueInputBuffer(index, 0, length, pts, 0);
        if (firstAudioInputPtsUs == Long.MIN_VALUE) firstAudioInputPtsUs = pts;
        lastAudioInputEndPtsUs = pts + durationUs;
        submittedAudioBytes += length;
        currentPcmOffset += length;
        if (currentPcmOffset >= currentPcm.length) {
            currentPcm = null;
            currentPcmOffset = 0;
            currentPcmTimelineStartUs = Long.MIN_VALUE;
        }
        return true;
    }

    private long normalizeAudioSourcePtsUs(long rawPtsUs) {
        long adjustment = audioSourcePtsAdjustmentUs;
        if (adjustment == Long.MIN_VALUE) {
            adjustment = initialAudioTimelineAdjustmentUs(timelineOriginUs, rawPtsUs,
                    audioSubscription.preciseSourceTimestamps);
            audioSourcePtsAdjustmentUs = adjustment;
        }
        int generation = cameraSource.getTimelineGeneration();
        if (isSourceTimestampReset(lastRawAudioSourcePtsUs, rawPtsUs)) {
            long continuation = lastAudioInputEndPtsUs == Long.MIN_VALUE
                    ? 0 : lastAudioInputEndPtsUs;
            adjustment = continuation - rawPtsUs;
            audioSourcePtsAdjustmentUs = adjustment;
            audioInputTimelineGeneration = generation;
            Log.w(TAG, "audio source timeline restarted generation=" + generation
                    + " raw=" + rawPtsUs + " continuation=" + continuation);
        } else if (generation != audioInputTimelineGeneration) {
            audioInputTimelineGeneration = generation;
        }
        lastRawAudioSourcePtsUs = rawPtsUs;
        return Math.max(0, rawPtsUs + adjustment);
    }

    private boolean dropAudioOverlapIfNeeded() {
        if (currentPcm == null || lastAudioInputEndPtsUs == Long.MIN_VALUE) return false;
        long candidatePtsUs = currentPcmTimelineStartUs
                + pcmDurationUs(currentPcmOffset);
        long overlapUs = audioOverlapToDropUs(candidatePtsUs, lastAudioInputEndPtsUs,
                audioJitterToleranceUs());
        if (overlapUs <= 0) return false;
        int frameBytes = audioSubscription.channelCount * audioSubscription.bytesPerSample;
        int remaining = currentPcm.length - currentPcmOffset;
        int skip = Math.min(remaining, pcmBytesForDurationCeil(overlapUs));
        skip -= skip % frameBytes;
        if (skip <= 0) return false;
        currentPcmOffset += skip;
        droppedOverlapBytes += skip;
        if (currentPcmOffset >= currentPcm.length) {
            currentPcm = null;
            currentPcmOffset = 0;
            currentPcmTimelineStartUs = Long.MIN_VALUE;
        }
        return true;
    }

    private long audioJitterToleranceUs() {
        return audioSubscription.preciseSourceTimestamps
                ? PRECISE_AUDIO_JITTER_TOLERANCE_US
                : CAPTURE_AUDIO_JITTER_TOLERANCE_US;
    }

    private int pcmBytesForDurationCeil(long durationUs) {
        long frameBytes = (long) audioSubscription.channelCount
                * audioSubscription.bytesPerSample;
        long frames = (Math.max(0, durationUs) * audioSubscription.sampleRate
                + 999_999L) / 1_000_000L;
        long bytes = frames * frameBytes;
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    private static void putSilence(ByteBuffer buffer, int length) {
        int longs = length / Long.BYTES;
        for (int index = 0; index < longs; index++) buffer.putLong(0L);
        for (int index = longs * Long.BYTES; index < length; index++) buffer.put((byte) 0);
    }

    static long audioGapToFillUs(long candidatePtsUs, long previousEndPtsUs,
                                 long toleranceUs) {
        if (previousEndPtsUs == Long.MIN_VALUE) return 0;
        long gapUs = candidatePtsUs - previousEndPtsUs;
        return gapUs > Math.max(0, toleranceUs) ? gapUs : 0;
    }

    static long audioOverlapToDropUs(long candidatePtsUs, long previousEndPtsUs,
                                     long toleranceUs) {
        if (previousEndPtsUs == Long.MIN_VALUE) return 0;
        long overlapUs = previousEndPtsUs - candidatePtsUs;
        return overlapUs > Math.max(0, toleranceUs) ? overlapUs : 0;
    }

    private long pcmDurationUs(long bytes) {
        long frameBytes = audioSubscription.channelCount * audioSubscription.bytesPerSample;
        return (bytes / frameBytes) * 1_000_000L / audioSubscription.sampleRate;
    }

    static long monotonicAudioInputPtsUs(long candidatePtsUs, long previousEndPtsUs) {
        return previousEndPtsUs == Long.MIN_VALUE
                ? Math.max(0, candidatePtsUs)
                : Math.max(previousEndPtsUs, candidatePtsUs);
    }

    private DrainResult drainOne(MediaCodec codecToDrain, MediaCodec.BufferInfo info,
                                 boolean audio) throws Exception {
        int index = codecToDrain.dequeueOutputBuffer(info, 0);
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return DrainResult.NONE;
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            int track = muxer.addTrack(codecToDrain.getOutputFormat());
            if (audio) audioTrack = track;
            else videoTrack = track;
            startMuxerWhenReady();
            return DrainResult.PROGRESSED;
        }
        if (index < 0) return DrainResult.NONE;
        ByteBuffer buffer = codecToDrain.getOutputBuffer(index);
        if (buffer == null) throw new IllegalStateException("编码器返回空输出缓冲区");
        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
        if (info.size > 0) {
            MediaCodec.BufferInfo normalized = normalizedInfo(info, audio);
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            writeOrQueue(audio ? audioTrack : videoTrack, buffer, normalized, audio);
            if (audio) audioSampleCount++;
            else videoSampleCount++;
        }
        codecToDrain.releaseOutputBuffer(index, false);
        return new DrainResult(true, eos);
    }

    private MediaCodec.BufferInfo normalizedInfo(MediaCodec.BufferInfo source, boolean audio) {
        long adjustment = audio ? audioPtsAdjustmentUs : videoPtsAdjustmentUs;
        long rawPtsUs = source.presentationTimeUs;
        long previousRawPtsUs = audio ? lastRawAudioPtsUs : lastRawVideoPtsUs;
        int currentGeneration = cameraSource.getTimelineGeneration();
        int trackGeneration = audio ? audioTimelineGeneration : videoTimelineGeneration;
        if (adjustment == Long.MIN_VALUE) {
            if (audio) {
                // Audio input is already mapped onto the compressed recording
                // timeline. Correct only a possible AAC encoder priming offset.
                if (firstAudioInputPtsUs != Long.MIN_VALUE
                        && Math.abs(rawPtsUs - firstAudioInputPtsUs)
                        <= AUDIO_ENCODER_PRIMING_TOLERANCE_US) {
                    adjustment = firstAudioInputPtsUs - rawPtsUs;
                } else {
                    adjustment = 0;
                }
                audioPtsAdjustmentUs = adjustment;
            } else {
                adjustment = initialTimelineAdjustmentUs(timelineOriginUs,
                        rawPtsUs);
                videoPtsAdjustmentUs = adjustment;
            }
        }

        /*
         * Network recording uses linked pause/resume: when fresh video stops,
         * decoded PCM is gated off as well. Remove the same stopped interval
         * from video PTS when a player generation changes, timestamps restart,
         * or the monotonic encoder clock contains a large forward gap.
         */
        if (!audio) {
            boolean timestampReset = isSourceTimestampReset(previousRawPtsUs, rawPtsUs);
            boolean generationChanged = currentGeneration != trackGeneration;
            // Only the network source gates decoded audio together with video.
            // UVC/camera audio uses a separate capture path, so compressing a
            // random encoder gap there would make those tracks drift.
            boolean forwardStall = cameraSource instanceof NetworkStreamSource
                    && isForwardVideoStall(previousRawPtsUs, rawPtsUs,
                    VIDEO_STALL_GAP_TOLERANCE_US);
            if (timestampReset || generationChanged || forwardStall) {
                long continuationPtsUs = continuationPtsUs();
                adjustment = continuationPtsUs - rawPtsUs;
                correctedTimelineAdjustmentUs = adjustment;
                correctedTimelineGeneration = currentGeneration;
                videoPtsAdjustmentUs = adjustment;
                videoTimelineGeneration = currentGeneration;
                Log.w(TAG, "paused video interval removed generation=" + currentGeneration
                        + " raw=" + rawPtsUs + " continuation=" + continuationPtsUs
                        + " reset=" + timestampReset + " gap=" + forwardStall);
            }
        }

        if (audio) lastRawAudioPtsUs = rawPtsUs;
        else lastRawVideoPtsUs = rawPtsUs;
        long previous = audio ? lastAudioMuxPtsUs : lastVideoMuxPtsUs;
        long normalizedPtsUs = Math.max(0, rawPtsUs + adjustment);
        if (previous >= 0 && normalizedPtsUs <= previous) {
            normalizedPtsUs = previous + 1;
        }
        if (audio) lastAudioMuxPtsUs = normalizedPtsUs;
        else lastVideoMuxPtsUs = normalizedPtsUs;
        MediaCodec.BufferInfo result = new MediaCodec.BufferInfo();
        result.set(source.offset, source.size, normalizedPtsUs, source.flags);
        return result;
    }

    private long continuationPtsUs() {
        long videoStepUs = Math.max(1L, 1_000_000L / Math.max(1, fps));
        long audioStepUs = audioSubscription == null
                ? 1L : Math.max(1L, 1_024_000_000L / audioSubscription.sampleRate);
        long afterVideo = lastVideoMuxPtsUs < 0 ? 0 : lastVideoMuxPtsUs + videoStepUs;
        long afterAudio = lastAudioMuxPtsUs < 0 ? 0 : lastAudioMuxPtsUs + audioStepUs;
        long afterQueuedAudio = lastAudioInputEndPtsUs == Long.MIN_VALUE
                ? 0 : lastAudioInputEndPtsUs;
        return Math.max(afterVideo, Math.max(afterAudio, afterQueuedAudio));
    }

    static boolean isSourceTimestampReset(long previousRawPtsUs, long currentRawPtsUs) {
        return previousRawPtsUs != Long.MIN_VALUE
                && currentRawPtsUs + SOURCE_TIMESTAMP_RESET_TOLERANCE_US
                < previousRawPtsUs;
    }

    static boolean isForwardVideoStall(long previousRawPtsUs, long currentRawPtsUs,
                                       long toleranceUs) {
        return previousRawPtsUs != Long.MIN_VALUE
                && currentRawPtsUs > previousRawPtsUs
                && currentRawPtsUs - previousRawPtsUs > Math.max(0, toleranceUs);
    }

    static long initialTimelineAdjustmentUs(long originPtsUs, long firstTrackPtsUs) {
        if (originPtsUs != Long.MIN_VALUE
                && timestampsShareClock(originPtsUs, firstTrackPtsUs)) {
            return -originPtsUs;
        }
        return -firstTrackPtsUs;
    }

    static long initialAudioTimelineAdjustmentUs(long originPtsUs, long firstTrackPtsUs,
                                                 boolean realtimeDecodedAudio) {
        // The network PCM tap is created before the recorder surface. Its next
        // callback can be a few hundred milliseconds after the first encoded
        // video frame even though both represent the same playback start.
        // Screen recorders start both output tracks at zero, then preserve
        // later monotonic-clock gaps. Do the same for decoded RTMP audio.
        return realtimeDecodedAudio
                ? -firstTrackPtsUs
                : initialTimelineAdjustmentUs(originPtsUs, firstTrackPtsUs);
    }

    static boolean timestampsShareClock(long firstPtsUs, long secondPtsUs) {
        long difference;
        try {
            difference = Math.subtractExact(firstPtsUs, secondPtsUs);
        } catch (ArithmeticException overflow) {
            return false;
        }
        return difference >= -CLOCK_DOMAIN_TOLERANCE_US
                && difference <= CLOCK_DOMAIN_TOLERANCE_US;
    }

    private void startMuxerWhenReady() throws Exception {
        int ready = (videoTrack >= 0 ? 1 : 0) + (audioTrack >= 0 ? 1 : 0);
        if (!muxerStarted && ready == expectedTracks) {
            muxer.start();
            muxerStarted = true;
            for (PendingSample sample : pendingSamples) {
                muxer.writeSampleData(sample.track, ByteBuffer.wrap(sample.data), sample.info);
            }
            pendingSamples.clear();
        }
    }

    private void writeOrQueue(int track, ByteBuffer buffer, MediaCodec.BufferInfo info,
                              boolean audio) throws Exception {
        if (!muxerStarted) {
            ByteBuffer copy = buffer.duplicate();
            byte[] bytes = new byte[info.size];
            copy.position(info.offset);
            copy.limit(info.offset + info.size);
            copy.get(bytes);
            MediaCodec.BufferInfo queuedInfo = new MediaCodec.BufferInfo();
            queuedInfo.set(0, bytes.length, info.presentationTimeUs, info.flags);
            pendingSamples.add(new PendingSample(track, bytes, queuedInfo));
            return;
        }
        try {
            muxer.writeSampleData(track, buffer, info);
        } catch (AviH264Muxer.FileLimitReachedException limitReached) {
            if (audio) throw limitReached;
            requestStop(null);
        }
    }

    private void cleanupCodecs() {
        detachSurface();
        if (audioSubscription != null) audioSubscription.close();
        stopAndRelease(videoEncoder);
        stopAndRelease(audioEncoder);
        videoEncoder = null;
        audioEncoder = null;
        if (muxer != null) muxer.release();
        if (inputSurface != null) inputSurface.release();
        inputSurface = null;
        audioSubscription = null;
        if (drainThread != null) drainThread.quitSafely();
    }

    private void releaseAudioOnly() {
        stopAndRelease(audioEncoder);
        audioEncoder = null;
        if (audioSubscription != null) audioSubscription.close();
        audioSubscription = null;
        audioActive = false;
        audioCaptureEnded.set(true);
        expectedTracks = 1;
    }

    private static void stopAndRelease(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
    }

    private void detachSurface() {
        if (surfaceAttached && inputSurface != null) {
            surfaceAttached = false;
            try {
                cameraSource.removeRecordingSurface(inputSurface);
            } catch (Exception ignored) {
            }
        }
    }

    private static final class PendingSample {
        final int track;
        final byte[] data;
        final MediaCodec.BufferInfo info;

        PendingSample(int track, byte[] data, MediaCodec.BufferInfo info) {
            this.track = track;
            this.data = data;
            this.info = info;
        }
    }

    private static final class DrainResult {
        static final DrainResult NONE = new DrainResult(false, false);
        static final DrainResult PROGRESSED = new DrainResult(true, false);
        final boolean progressed;
        final boolean eos;

        DrainResult(boolean progressed, boolean eos) {
            this.progressed = progressed;
            this.eos = eos;
        }
    }

    static final class EmptyRecordingException extends Exception {
        EmptyRecordingException(String message) {
            super(message);
        }
    }
}
