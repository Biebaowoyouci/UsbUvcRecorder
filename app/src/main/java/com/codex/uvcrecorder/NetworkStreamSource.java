package com.codex.uvcrecorder;

import android.content.Context;
import android.graphics.Color;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.codex.uvcrecorder.flv.CompatibleFlvExtractor;
import com.serenegiant.opengl.renderer.RendererHolder;
import com.serenegiant.opengl.renderer.RendererHolderCallback;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plays one RTMP input into the same GPU surface router used by UVC inputs.
 * The router supplies independent preview, recorder, RTMP-push and HDMI surfaces.
 */
@OptIn(markerClass = UnstableApi.class)
final class NetworkStreamSource implements UvcSurfaceSource {
    private static final String TAG = "NetworkStreamSource";
    interface Listener {
        void onConnecting(String detail);

        void onReady(int width, int height, int fps, String formatName,
                     boolean audioAvailable);

        void onAudioAvailable();

        void onError(String message, Throwable error);
    }

    private static final int SCREEN_SURFACE_ID = 0x52544D50;
    private static final long FRAME_STALL_MS = 3_000;
    private static final long FIRST_FRAME_NO_DATA_TIMEOUT_MS = 3_000;
    private static final long FIRST_FRAME_DATA_IDLE_TIMEOUT_MS = 3_000;
    private static final long FIRST_FRAME_MAX_WAIT_MS = 6_000;
    private static final long HEALTH_CHECK_MS = 500;
    private static final long RECONNECT_DELAY_MS = 80;
    private static final long REPEATED_TIMESTAMP_STALL_MS = 1_500L;
    private static final long PLAYER_RELEASE_TIMEOUT_MS = 750L;
    private static final long LIVE_TARGET_OFFSET_MS = 700L;
    private static final long LIVE_MIN_OFFSET_MS = 200L;
    private static final long LIVE_MAX_OFFSET_MS = 2_000L;
    private static final long LIVE_HARD_RESYNC_OFFSET_MS = 3_500L;
    private static final long LIVE_RESYNC_COOLDOWN_MS = 3_000L;
    private static final int LIVE_AUDIO_QUEUE_MS = 250;
    private static final long BACKWARD_TIMELINE_TOLERANCE_MS = 250L;
    private static final long AUDIO_VIDEO_FLOW_GAP_MS = 250L;
    private static final long FPS_MEASUREMENT_WINDOW_MS = 1_500L;

    private final Context context;
    private final String url;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object audioLock = new Object();
    private final List<PcmAudioSubscription> audioSubscriptions = new ArrayList<>();
    private final ArrayDeque<PendingDecodedAudio> pendingDecodedAudio = new ArrayDeque<>();
    private final Map<Integer, int[]> recordingSurfaces = new HashMap<>();

    private ExoPlayer player;
    private Player.Listener activePlayerListener;
    private RendererHolder router;
    private Surface screenSurface;
    private boolean screenSurfaceAttached;
    private Surface displaySurface;
    private int screenWidth;
    private int screenHeight;
    private int displaySurfaceId;
    private int displayWidth;
    private int displayHeight;
    private volatile int inputWidth;
    private volatile int inputHeight;
    private volatile int fps = 30;
    private volatile String formatName = "RTMP";
    private volatile int outputRotation;
    private volatile long lastFrameAt;
    private volatile long lastFrameRealtimeUs = Long.MIN_VALUE;
    private volatile boolean videoFlowing;
    private volatile int playerGeneration;
    private volatile int timelineGeneration;
    private int lastAcceptedFrameGeneration = -1;
    private int lastReadyWidth;
    private int lastReadyHeight;
    private int lastReadyFps;
    private String lastReadyFormatName;
    private int lastReadyGeneration = -1;
    private int retryCount;
    private long repeatedTimestampStartedAt;
    private int audioSampleRate;
    private int audioChannelCount;
    private int audioEncoding;
    private long lastSurfaceTimestampNs;
    private long playerStartedAt;
    private volatile long lastNetworkDataAt;
    private volatile long receivedNetworkBytes;
    private long lastLiveResyncAt;
    private long fpsWindowStartedAt;
    private int fpsWindowFrameCount;
    private long nextDecodedAudioPtsUs = C.TIME_UNSET;
    private Runnable pendingReconnect;
    private boolean reconnectScheduled;
    private volatile boolean released;

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            long now = SystemClock.elapsedRealtime();
            if (correctLiveLatency(now)) return;
            if (shouldReconnectAfterFirstFrame(lastFrameAt, now)) {
                reconnect("网络流画面中断，正在重新连接");
                return;
            }
            if (lastFrameAt <= 0 && shouldReconnectBeforeFirstFrame(
                    playerStartedAt, lastNetworkDataAt, now)) {
                reconnect(lastNetworkDataAt > 0
                        ? "已收到网络数据，但没有等到可解码的视频关键帧"
                        : "服务器没有返回直播音视频数据");
                return;
            }
            mainHandler.postDelayed(this, HEALTH_CHECK_MS);
        }
    };

    NetworkStreamSource(Context context, String url, Listener listener) {
        this.context = context.getApplicationContext();
        this.url = url;
        this.listener = listener;
    }

    void start() {
        if (released || player != null) return;
        listener.onConnecting(isHttpFlvUrl(url)
                ? "正在连接 HTTP-FLV 服务器…"
                : "正在连接 RTMP 服务器…");
        try {
            router = new RendererHolder(1920, 1080, new RendererHolderCallback() {
                @Override
                public void onPrimarySurfaceCreate(Surface surface) {
                    // RendererHolder creates its Surface before it marks the EGL
                    // worker as running. Defer Media3 initialization until that
                    // final state change has reached the main queue.
                    mainHandler.post(NetworkStreamSource.this::startPlayerWhenRouterReady);
                }

                @Override
                public void onFrameAvailable() {
                    RendererHolder holder = router;
                    long timestampNs = 0;
                    if (holder != null) {
                        try {
                            timestampNs = holder.getPrimarySurfaceTexture().getTimestamp();
                        } catch (Throwable ignored) {
                        }
                    }
                    int generation = playerGeneration;
                    long finalTimestampNs = timestampNs;
                    mainHandler.post(() -> onRouterFrame(generation, finalTimestampNs));
                }

                @Override
                public void onPrimarySurfaceDestroy() {
                    }
                });
            // Fallback for vendor schedulers that delay the surface callback.
            mainHandler.postDelayed(this::startPlayerWhenRouterReady, 80L);
        } catch (Throwable error) {
            releasePlayerAndRouter();
            listener.onError(readable(error), error);
        }
    }

    boolean matchesUrl(String candidate) {
        return candidate != null && url.equals(candidate.trim());
    }

    private void startPlayerWhenRouterReady() {
        if (released || player != null || reconnectScheduled) return;
        RendererHolder holder = router;
        if (holder == null) return;
        if (!holder.isRunning()) {
            mainHandler.postDelayed(this::startPlayerWhenRouterReady, 50L);
            return;
        }
        attachPendingScreenSurface();
        try {
            startFreshPlayer();
        } catch (Throwable error) {
            Log.w(TAG, "RTMP player initialization failed: " + readable(error), error);
            listener.onError(readable(error), error);
            reconnect("RTMP player initialization failed");
        }
    }

    private void startFreshPlayer() {
        if (released) return;
        RendererHolder holder = router;
        if (holder == null || !holder.isRunning()) {
            throw new IllegalStateException("RTMP 视频路由器未就绪");
        }
        final int generation = ++playerGeneration;
        ++timelineGeneration;
        lastFrameAt = 0;
        lastFrameRealtimeUs = Long.MIN_VALUE;
        videoFlowing = false;
        lastAcceptedFrameGeneration = -1;
        playerStartedAt = SystemClock.elapsedRealtime();
        lastSurfaceTimestampNs = 0;
        repeatedTimestampStartedAt = 0;
        lastNetworkDataAt = 0;
        receivedNetworkBytes = 0;
        lastLiveResyncAt = 0;
        fpsWindowStartedAt = 0;
        fpsWindowFrameCount = 0;
        clearAudioQueues();
        clearStaleOutputs();

        AtomicLong sinkBufferPtsUs = new AtomicLong(C.TIME_UNSET);
        TeeAudioProcessor teeAudioProcessor = new TeeAudioProcessor(
                new TeeAudioProcessor.AudioBufferSink() {
                    @Override
                    public void flush(int sampleRateHz, int channelCount, int encoding) {
                        onAudioFormat(generation, sampleRateHz, channelCount, encoding);
                    }

                    @Override
                    public void handleBuffer(ByteBuffer buffer) {
                        onAudioBuffer(generation, buffer, sinkBufferPtsUs.get());
                    }
                });
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected AudioSink buildAudioSink(Context sinkContext, boolean enableFloatOutput,
                                               boolean enableAudioOutputPlaybackParams) {
                AudioSink decodedPcmSink = new DefaultAudioSink.Builder(sinkContext)
                        .setAudioProcessors(new AudioProcessor[]{teeAudioProcessor})
                        .build();
                return new ForwardingAudioSink(decodedPcmSink) {
                    @Override
                    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs,
                                                int encodedAccessUnitCount)
                            throws AudioSink.InitializationException,
                            AudioSink.WriteException {
                        sinkBufferPtsUs.set(presentationTimeUs);
                        return super.handleBuffer(buffer, presentationTimeUs,
                                encodedAccessUnitCount);
                    }

                    @Override
                    public void handleDiscontinuity() {
                        resetDecodedAudioTimeline();
                        super.handleDiscontinuity();
                    }

                    @Override
                    public void flush() {
                        resetDecodedAudioTimeline();
                        super.flush();
                    }
                };
            }
        }.setEnableDecoderFallback(true);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(300, 1_000, 100, 200)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
                .setBackBuffer(0, false)
                .build();
        DefaultLivePlaybackSpeedControl liveSpeedControl =
                new DefaultLivePlaybackSpeedControl.Builder()
                        .setFallbackMinPlaybackSpeed(0.97f)
                        .setFallbackMaxPlaybackSpeed(1.05f)
                        .setMinUpdateIntervalMs(250L)
                        .setMaxLiveOffsetErrorMsForUnitSpeed(60L)
                        .setTargetLiveOffsetIncrementOnRebufferMs(100L)
                        .build();
        Player.Listener playerListener = new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                NetworkStreamSource.this.onVideoSizeChanged(generation, videoSize);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                NetworkStreamSource.this.onPlaybackStateChanged(generation, playbackState);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                NetworkStreamSource.this.onPlayerError(generation, error);
            }

            @Override
            public void onRenderedFirstFrame() {
                NetworkStreamSource.this.onRenderedFirstFrame(generation);
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition,
                                                int reason) {
                NetworkStreamSource.this.onPositionDiscontinuity(generation,
                        oldPosition.positionMs, newPosition.positionMs, reason);
            }
        };
        ExoPlayer next = new ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setLivePlaybackSpeedControl(liveSpeedControl)
                .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                .build();
        activePlayerListener = playerListener;
        next.addListener(playerListener);
        next.setVideoSurface(holder.getPrimarySurface());
        DataSource.Factory dataSourceFactory = createDataSourceFactory(generation);
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                        .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                        .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.05f)
                        .build())
                .build();
        ProgressiveMediaSource.Factory mediaSourceFactory = isHttpFlvUrl(url)
                ? new ProgressiveMediaSource.Factory(dataSourceFactory,
                        CompatibleFlvExtractor.FACTORY)
                : new ProgressiveMediaSource.Factory(dataSourceFactory);
        ProgressiveMediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);
        next.setMediaSource(mediaSource);
        player = next;
        next.prepare();
        next.play();
        scheduleHealthCheck();
    }

    private DataSource.Factory createDataSourceFactory(int generation) {
        TransferListener transferListener = createTransferListener(generation);
        if (isHttpFlvUrl(url)) {
            return new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android) USB-UVC-Recorder/1.6")
                    .setConnectTimeoutMs((int) FIRST_FRAME_NO_DATA_TIMEOUT_MS)
                    .setReadTimeoutMs((int) FRAME_STALL_MS)
                    .setAllowCrossProtocolRedirects(true)
                    .setTransferListener(transferListener);
        }
        return new RtmpDataSource.Factory().setTransferListener(transferListener);
    }

    static boolean isHttpFlvUrl(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(java.util.Locale.US);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private void onVideoSizeChanged(int generation, VideoSize videoSize) {
        if (released || generation != playerGeneration) return;
        if (videoSize.width <= 0 || videoSize.height <= 0) return;
        inputWidth = even(videoSize.width);
        inputHeight = even(videoSize.height);
        ExoPlayer current = player;
        Format format = current == null ? null : current.getVideoFormat();
        if (format != null) {
            if (format.frameRate > 0) fps = Math.max(1, Math.round(format.frameRate));
            formatName = codecLabel(format.sampleMimeType, isHttpFlvUrl(url));
        }
        applyAllTransforms();
        notifyReadyIfPossible();
    }

    private void onPlaybackStateChanged(int generation, int playbackState) {
        if (released || generation != playerGeneration) return;
        Log.i(TAG, "player generation=" + generation + " state="
                + playbackStateName(playbackState) + " bytes=" + receivedNetworkBytes);
        if (playbackState == Player.STATE_BUFFERING) {
            videoFlowing = false;
            clearAudioQueues();
        }
        if (playbackState == Player.STATE_READY) {
            if (lastFrameAt <= 0) {
                listener.onConnecting("网络流已打开，正在等待首个视频关键帧…");
            }
            notifyReadyIfPossible();
        } else if (playbackState == Player.STATE_BUFFERING
                && lastFrameAt <= 0 && lastNetworkDataAt > 0) {
            listener.onConnecting("已收到网络数据，正在缓冲视频关键帧…");
        } else if (playbackState == Player.STATE_ENDED) {
            reconnect("网络流已结束，正在重新连接");
        }
    }

    private void onPlayerError(int generation, PlaybackException error) {
        if (released || generation != playerGeneration) return;
        Log.w(TAG, "RTMP playback failed [" + error.errorCode + "]: "
                + readable(error), error);
        reconnect("拉流失败，正在重新连接：" + readable(error));
    }

    private void onRenderedFirstFrame(int generation) {
        if (released || generation != playerGeneration || reconnectScheduled) return;
        lastFrameAt = SystemClock.elapsedRealtime();
        retryCount = 0;
        notifyReadyIfPossible();
    }

    private void onPositionDiscontinuity(int generation, long oldPositionMs,
                                         long newPositionMs, int reason) {
        if (released || generation != playerGeneration) return;
        Log.i(TAG, "timeline discontinuity reason=" + reason + " "
                + oldPositionMs + "ms -> " + newPositionMs + "ms");
        if (isBackwardTimelineJump(oldPositionMs, newPositionMs)) {
            ++timelineGeneration;
            clearAudioQueues();
            clearStaleOutputs();
            lastSurfaceTimestampNs = 0;
            repeatedTimestampStartedAt = 0;
        }
    }

    private TransferListener createTransferListener(int generation) {
        return new TransferListener() {
            @Override
            public void onTransferInitializing(DataSource source, DataSpec dataSpec,
                                               boolean isNetwork) {
                postConnecting(generation, isHttpFlvUrl(url)
                        ? "正在连接 HTTP-FLV 服务器…" : "正在进行 RTMP 握手…");
            }

            @Override
            public void onTransferStart(DataSource source, DataSpec dataSpec,
                                        boolean isNetwork) {
                postConnecting(generation, isHttpFlvUrl(url)
                        ? "HTTP-FLV 服务器已响应，正在等待直播数据…"
                        : "RTMP 服务器已响应，正在等待直播数据…");
            }

            @Override
            public void onBytesTransferred(DataSource source, DataSpec dataSpec,
                                           boolean isNetwork, int bytesTransferred) {
                if (released || generation != playerGeneration || bytesTransferred <= 0) {
                    return;
                }
                boolean firstData = receivedNetworkBytes <= 0;
                receivedNetworkBytes += bytesTransferred;
                lastNetworkDataAt = SystemClock.elapsedRealtime();
                if (firstData) {
                    postConnecting(generation, "已收到直播数据，正在等待视频关键帧…");
                }
            }

            @Override
            public void onTransferEnd(DataSource source, DataSpec dataSpec,
                                      boolean isNetwork) {
                Log.i(TAG, "RTMP transfer ended generation=" + generation
                        + " bytes=" + receivedNetworkBytes);
                mainHandler.post(() -> {
                    if (!released && generation == playerGeneration
                            && !reconnectScheduled) {
                        reconnect("RTMP 连接已断开，正在立即重连");
                    }
                });
            }
        };
    }

    private void postConnecting(int generation, String detail) {
        mainHandler.post(() -> {
            // Transfer callbacks are posted from the loader thread. A delayed
            // "buffering" callback must never cover a frame that has already
            // reached the display.
            if (!released && generation == playerGeneration
                    && !reconnectScheduled && lastFrameAt <= 0) {
                listener.onConnecting(detail);
            }
        });
    }

    private void reconnect(String status) {
        if (released || reconnectScheduled) return;
        reconnectScheduled = true;
        videoFlowing = false;
        retryCount++;
        lastFrameAt = 0;
        clearAudioQueues();
        clearStaleOutputs();
        String retryStatus = status + "，第 " + retryCount + " 次重连…";
        Log.w(TAG, retryStatus + " bytes=" + receivedNetworkBytes);
        listener.onConnecting(retryStatus);
        mainHandler.removeCallbacks(healthCheck);
        final int failedGeneration = playerGeneration;
        pendingReconnect = () -> {
            pendingReconnect = null;
            if (released || failedGeneration != playerGeneration) {
                reconnectScheduled = false;
                return;
            }
            try {
                ++playerGeneration;
                releasePlayerOnly();
                reconnectScheduled = false;
                startFreshPlayer();
            } catch (Throwable error) {
                reconnectScheduled = false;
                Log.w(TAG, status + "；" + readable(error), error);
                listener.onConnecting("重连初始化失败，继续尝试…");
                reconnect("继续尝试连接网络流");
            }
        };
        mainHandler.postDelayed(pendingReconnect, RECONNECT_DELAY_MS);
    }

    private void onRouterFrame(int generation, long timestampNs) {
        if (released || generation != playerGeneration || reconnectScheduled) return;
        boolean firstForGeneration = generation != lastAcceptedFrameGeneration;
        if (!firstForGeneration
                && isRepeatedSurfaceTimestamp(lastSurfaceTimestampNs, timestampNs)) {
            long now = SystemClock.elapsedRealtime();
            if (repeatedTimestampStartedAt <= 0) repeatedTimestampStartedAt = now;
            if (isRepeatedTimestampStalled(repeatedTimestampStartedAt, now)) {
                reconnect("检测到重复画面，正在重建拉流解码器");
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean resumedAfterPause = firstForGeneration
                || isAudioVideoFlowGap(lastFrameAt, now, AUDIO_VIDEO_FLOW_GAP_MS);
        synchronized (audioLock) {
            if (resumedAfterPause) {
                pendingDecodedAudio.clear();
            } else {
                commitPendingDecodedAudioLocked();
            }
        }
        lastAcceptedFrameGeneration = generation;
        if (timestampNs > 0) lastSurfaceTimestampNs = timestampNs;
        repeatedTimestampStartedAt = 0;
        lastFrameAt = now;
        lastFrameRealtimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
        videoFlowing = true;
        updateMeasuredFrameRate(now);
        retryCount = 0;
        notifyReadyIfPossible();
    }

    private void updateMeasuredFrameRate(long nowMs) {
        if (fpsWindowStartedAt <= 0 || nowMs <= fpsWindowStartedAt) {
            fpsWindowStartedAt = nowMs;
            fpsWindowFrameCount = 1;
            return;
        }
        fpsWindowFrameCount++;
        long elapsedMs = nowMs - fpsWindowStartedAt;
        if (elapsedMs < FPS_MEASUREMENT_WINDOW_MS) return;
        int measured = estimateRenderedFps(fpsWindowFrameCount, elapsedMs);
        if (measured >= 1 && measured <= 120) fps = measured;
        fpsWindowStartedAt = nowMs;
        fpsWindowFrameCount = 1;
    }

    private boolean correctLiveLatency(long nowMs) {
        ExoPlayer current = player;
        if (current == null || current.getPlaybackState() != Player.STATE_READY
                || !current.isCurrentMediaItemLive()) {
            return false;
        }
        long liveOffsetMs = current.getCurrentLiveOffset();
        if (!shouldHardResyncLiveOffset(liveOffsetMs)
                || nowMs - lastLiveResyncAt < LIVE_RESYNC_COOLDOWN_MS) {
            return false;
        }
        lastLiveResyncAt = nowMs;
        Log.w(TAG, "live latency " + liveOffsetMs
                + "ms exceeds limit; discarding stale timeline");
        clearAudioQueues();
        clearStaleOutputs();
        lastSurfaceTimestampNs = 0;
        repeatedTimestampStartedAt = 0;
        if (current.isCurrentMediaItemSeekable()) {
            lastFrameAt = 0;
            playerStartedAt = nowMs;
            current.seekToDefaultPosition();
            listener.onConnecting("直播延迟过高，正在追赶最新画面…");
            return false;
        }
        reconnect("直播延迟过高，正在重新连接最新画面");
        return true;
    }

    private void clearStaleOutputs() {
        // Preserve the last decoded picture in preview while recording is
        // active, but do not submit it to the encoder again. Audio is paused
        // at the same time and both tracks resume on the next fresh frame.
        if (hasRecordingSurfaces()) return;
        RendererHolder holder = router;
        if (holder == null || !holder.isRunning()) return;
        try {
            holder.clearSlaveSurfaceAll(Color.BLACK);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to clear stale RTMP output surfaces", error);
        }
    }

    private void scheduleHealthCheck() {
        mainHandler.removeCallbacks(healthCheck);
        mainHandler.postDelayed(healthCheck, HEALTH_CHECK_MS);
    }

    private void notifyReadyIfPossible() {
        if (released || lastFrameAt <= 0 || inputWidth <= 0 || inputHeight <= 0) return;
        if (lastReadyGeneration == playerGeneration
                && lastReadyWidth == inputWidth && lastReadyHeight == inputHeight
                && lastReadyFps == fps
                && java.util.Objects.equals(lastReadyFormatName, formatName)) return;
        lastReadyGeneration = playerGeneration;
        lastReadyWidth = inputWidth;
        lastReadyHeight = inputHeight;
        lastReadyFps = fps;
        lastReadyFormatName = formatName;
        listener.onReady(inputWidth, inputHeight, Math.max(1, fps), formatName,
                isAudioAvailable());
    }

    @Override
    public void addRecordingSurface(Surface surface) {
        addRecordingSurface(surface, getRecordingWidth(inputWidth, inputHeight),
                getRecordingHeight(inputWidth, inputHeight));
    }

    @Override
    public void addRecordingSurface(Surface surface, int width, int height) {
        RendererHolder holder = requireRouter(surface);
        int id = surface.hashCode();
        holder.addSlaveSurface(id, surface, true);
        synchronized (recordingSurfaces) {
            recordingSurfaces.put(id, new int[]{width, height});
        }
        applyTransform(holder, id, width, height);
    }

    @Override
    public void removeRecordingSurface(Surface surface) {
        if (surface == null) return;
        int id = surface.hashCode();
        synchronized (recordingSurfaces) {
            recordingSurfaces.remove(id);
        }
        RendererHolder holder = router;
        if (holder != null) {
            try {
                holder.removeSlaveSurface(id);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void setScreenSurface(Surface surface, int width, int height) {
        screenWidth = width;
        screenHeight = height;
        RendererHolder holder = router;
        if (screenSurface != surface || surface == null) {
            Surface previous = screenSurface;
            boolean wasAttached = screenSurfaceAttached;
            screenSurface = surface;
            screenSurfaceAttached = false;
            if (holder != null && wasAttached && previous != null) {
                try {
                    holder.removeSlaveSurface(SCREEN_SURFACE_ID);
                } catch (Throwable ignored) {
                }
            }
        }
        attachPendingScreenSurface();
    }

    private void attachPendingScreenSurface() {
        if (released) return;
        RendererHolder holder = router;
        Surface desired = screenSurface;
        if (holder == null || !holder.isRunning() || desired == null
                || !desired.isValid() || screenSurfaceAttached) {
            return;
        }
        try {
            try {
                holder.removeSlaveSurface(SCREEN_SURFACE_ID);
            } catch (Throwable ignored) {
            }
            holder.addSlaveSurface(SCREEN_SURFACE_ID, desired, false);
            screenSurfaceAttached = true;
            applyTransform(holder, SCREEN_SURFACE_ID, screenWidth, screenHeight);
        } catch (Throwable error) {
            screenSurfaceAttached = false;
            mainHandler.postDelayed(this::attachPendingScreenSurface, 120L);
        }
    }

    @Override
    public void addDisplaySurface(Surface surface, int width, int height) {
        RendererHolder holder = requireRouter(surface);
        removeDisplaySurface(displaySurface);
        int id = surface.hashCode() ^ 0x48444D49;
        holder.addSlaveSurface(id, surface, false);
        displaySurface = surface;
        displaySurfaceId = id;
        displayWidth = width;
        displayHeight = height;
        applyTransform(holder, id, width, height);
    }

    @Override
    public void removeDisplaySurface(Surface surface) {
        if (displaySurface == null || (surface != null && surface != displaySurface)) return;
        RendererHolder holder = router;
        int id = displaySurfaceId;
        displaySurface = null;
        displaySurfaceId = 0;
        displayWidth = 0;
        displayHeight = 0;
        if (holder != null && id != 0) {
            try {
                holder.removeSlaveSurface(id);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void setOutputRotation(int degrees) {
        int normalized = Math.floorMod(degrees, 360);
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException("旋转角度必须是 90° 的倍数");
        }
        outputRotation = normalized;
        applyAllTransforms();
    }

    @Override
    public int getOutputRotation() {
        return outputRotation;
    }

    @Override
    public long getVideoTimelinePositionUs() {
        return lastFrameRealtimeUs;
    }

    @Override
    public int getTimelineGeneration() {
        return timelineGeneration;
    }

    @Override
    public PcmAudioSubscription subscribeAudio(Context ignored) {
        synchronized (audioLock) {
            if (!isAudioAvailableLocked()) {
                throw new IllegalStateException("当前网络流没有可录制的 PCM 音频");
            }
            PcmAudioSubscription subscription = new PcmAudioSubscription(audioSampleRate,
                    audioChannelCount, 2, 16_384, "RTMP 网络流", -1, null,
                    true, this::removeAudioSubscription);
            audioSubscriptions.add(subscription);
            return subscription;
        }
    }

    @Override
    public boolean requiresRecordAudioPermission() {
        return false;
    }

    private void onAudioFormat(int generation, int sampleRateHz, int channelCount,
                               int encoding) {
        if (released || generation != playerGeneration) return;
        boolean becameAvailable;
        synchronized (audioLock) {
            if (released || generation != playerGeneration) return;
            boolean wasAvailable = isAudioAvailableLocked();
            boolean changed = audioSampleRate > 0
                    && (audioSampleRate != sampleRateHz || audioChannelCount != channelCount
                    || audioEncoding != encoding);
            audioSampleRate = sampleRateHz;
            audioChannelCount = channelCount;
            audioEncoding = encoding;
            if (audioSubscriptions.isEmpty()) nextDecodedAudioPtsUs = C.TIME_UNSET;
            if (changed) {
                IllegalStateException failure =
                        new IllegalStateException("网络流音频格式已变化，请开始新录制");
                for (PcmAudioSubscription subscription : audioSubscriptions) {
                    subscription.failure = failure;
                }
            }
            becameAvailable = !wasAvailable && isAudioAvailableLocked();
        }
        if (becameAvailable) {
            mainHandler.post(() -> {
                if (!released && generation == playerGeneration) listener.onAudioAvailable();
            });
        }
    }

    private void onAudioBuffer(int generation, ByteBuffer buffer,
                               long sourcePresentationTimeUs) {
        if (released || generation != playerGeneration) return;
        if (!shouldRecordDecodedAudio(videoFlowing, reconnectScheduled)) return;
        ByteBuffer copy = buffer.duplicate();
        if (!copy.hasRemaining()) return;
        byte[] pcm = new byte[copy.remaining()];
        copy.get(pcm);
        synchronized (audioLock) {
            if (released || generation != playerGeneration) return;
            if (!isAudioAvailableLocked()) return;
            if (audioSubscriptions.isEmpty()) return;
            // Hold decoded PCM until a following video frame confirms that the
            // picture is still moving. If video stalls, these uncommitted
            // blocks are discarded when playback buffers/reconnects or when
            // the first resumed frame arrives.
            pendingDecodedAudio.addLast(
                    new PendingDecodedAudio(pcm, sourcePresentationTimeUs));
        }
    }

    private void commitPendingDecodedAudioLocked() {
        while (!pendingDecodedAudio.isEmpty()) {
            PendingDecodedAudio pending = pendingDecodedAudio.removeFirst();
            int frameBytes = Math.max(1, audioChannelCount * 2);
            long durationUs = (pending.pcm.length / frameBytes) * 1_000_000L
                    / Math.max(1, audioSampleRate);
            long chunkPtsUs = nextDecodedAudioPtsUs;
            if (chunkPtsUs == C.TIME_UNSET) {
                chunkPtsUs = pending.sourcePresentationTimeUs == C.TIME_UNSET
                        ? 0 : Math.max(0, pending.sourcePresentationTimeUs);
            }
            nextDecodedAudioPtsUs = chunkPtsUs + durationUs;
            for (PcmAudioSubscription subscription : audioSubscriptions) {
                trimLiveAudioQueue(subscription, pending.pcm.length);
                subscription.offer(pending.pcm, chunkPtsUs);
            }
        }
    }

    private void resetDecodedAudioTimeline() {
        synchronized (audioLock) {
            pendingDecodedAudio.clear();
            if (audioSubscriptions.isEmpty()) nextDecodedAudioPtsUs = C.TIME_UNSET;
        }
    }

    private void trimLiveAudioQueue(PcmAudioSubscription subscription,
                                    int incomingBytes) {
        int maximum = maxLiveAudioQueueBytes(subscription.sampleRate,
                subscription.channelCount, subscription.bytesPerSample);
        int queued = Math.max(0, incomingBytes);
        for (byte[] pending : subscription.queue) {
            if (pending != null) queued += pending.length;
        }
        while (queued > maximum) {
            byte[] stale = subscription.queue.poll();
            if (stale == null) break;
            subscription.takeSourcePresentationTimeUs(stale);
            queued -= stale.length;
        }
    }

    private void clearAudioQueues() {
        synchronized (audioLock) {
            pendingDecodedAudio.clear();
            if (audioSubscriptions.isEmpty()) nextDecodedAudioPtsUs = C.TIME_UNSET;
        }
    }

    private boolean hasRecordingSurfaces() {
        synchronized (recordingSurfaces) {
            return !recordingSurfaces.isEmpty();
        }
    }

    private void removeAudioSubscription(PcmAudioSubscription subscription) {
        synchronized (audioLock) {
            audioSubscriptions.remove(subscription);
            if (audioSubscriptions.isEmpty()) {
                pendingDecodedAudio.clear();
                nextDecodedAudioPtsUs = C.TIME_UNSET;
            }
        }
    }

    private boolean isAudioAvailable() {
        synchronized (audioLock) {
            return isAudioAvailableLocked();
        }
    }

    private boolean isAudioAvailableLocked() {
        return audioSampleRate > 0 && audioChannelCount > 0
                && audioEncoding == C.ENCODING_PCM_16BIT;
    }

    private RendererHolder requireRouter(Surface surface) {
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("输出 Surface 无效");
        }
        RendererHolder holder = router;
        if (released || holder == null || !holder.isRunning()) {
            throw new IllegalStateException("RTMP 网络流画面尚未就绪");
        }
        return holder;
    }

    private void applyAllTransforms() {
        RendererHolder holder = router;
        if (holder == null || !holder.isRunning()) return;
        if (screenSurface != null) {
            attachPendingScreenSurface();
            if (screenSurfaceAttached) {
                applyTransform(holder, SCREEN_SURFACE_ID, screenWidth, screenHeight);
            }
        }
        if (displaySurface != null && displaySurfaceId != 0) {
            applyTransform(holder, displaySurfaceId, displayWidth, displayHeight);
        }
        synchronized (recordingSurfaces) {
            for (Map.Entry<Integer, int[]> entry : recordingSurfaces.entrySet()) {
                int[] size = entry.getValue();
                applyTransform(holder, entry.getKey(), size[0], size[1]);
            }
        }
    }

    private void applyTransform(RendererHolder holder, int id, int targetWidth,
                                int targetHeight) {
        if (inputWidth <= 0 || inputHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return;
        }
        float[] scale = VideoLayout.centerCropScale(inputWidth, inputHeight,
                outputRotation, targetWidth, targetHeight);
        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        Matrix.scaleM(matrix, 0, scale[0], scale[1], 1f);
        Matrix.rotateM(matrix, 0, outputRotation, 0f, 0f, -1f);
        try {
            holder.setSlaveMvpMatrix(id, matrix);
        } catch (Throwable ignored) {
        }
    }

    void release() {
        if (released) return;
        released = true;
        ++playerGeneration;
        mainHandler.removeCallbacksAndMessages(null);
        pendingReconnect = null;
        reconnectScheduled = false;
        videoFlowing = false;
        synchronized (audioLock) {
            IllegalStateException failure = new IllegalStateException("RTMP 网络流已关闭");
            for (PcmAudioSubscription subscription : audioSubscriptions) {
                subscription.failure = failure;
            }
            audioSubscriptions.clear();
        }
        releasePlayerAndRouter();
    }

    private void releasePlayerAndRouter() {
        releasePlayerOnly();
        RendererHolder currentRouter = router;
        router = null;
        if (currentRouter != null) {
            try {
                currentRouter.release();
            } catch (Throwable ignored) {
            }
        }
        screenSurface = null;
        screenSurfaceAttached = false;
        displaySurface = null;
        synchronized (recordingSurfaces) {
            recordingSurfaces.clear();
        }
    }

    private void releasePlayerOnly() {
        ExoPlayer currentPlayer = player;
        Player.Listener currentListener = activePlayerListener;
        player = null;
        activePlayerListener = null;
        if (currentPlayer != null) {
            try {
                if (currentListener != null) currentPlayer.removeListener(currentListener);
                currentPlayer.stop();
                currentPlayer.clearVideoSurface();
                currentPlayer.release();
            } catch (Throwable ignored) {
            }
        }
        clearAudioQueues();
    }

    private static int even(int value) {
        return Math.max(2, value - Math.abs(value % 2));
    }

    static boolean isRepeatedSurfaceTimestamp(long previousTimestampNs,
                                              long currentTimestampNs) {
        return previousTimestampNs > 0 && currentTimestampNs > 0
                && currentTimestampNs <= previousTimestampNs;
    }

    static boolean isRepeatedTimestampStalled(long firstRepeatedAtMs, long nowMs) {
        return firstRepeatedAtMs > 0 && nowMs >= firstRepeatedAtMs
                && nowMs - firstRepeatedAtMs >= REPEATED_TIMESTAMP_STALL_MS;
    }

    static boolean shouldReconnectAfterFirstFrame(long lastFrameAtMs, long nowMs) {
        return lastFrameAtMs > 0 && nowMs >= lastFrameAtMs
                && nowMs - lastFrameAtMs >= FRAME_STALL_MS;
    }

    static boolean shouldRecordDecodedAudio(boolean videoFlowing,
                                            boolean reconnectScheduled) {
        return videoFlowing && !reconnectScheduled;
    }

    static boolean isAudioVideoFlowGap(long previousFrameAtMs, long currentFrameAtMs,
                                       long toleranceMs) {
        return previousFrameAtMs > 0 && currentFrameAtMs > previousFrameAtMs
                && currentFrameAtMs - previousFrameAtMs > Math.max(0, toleranceMs);
    }

    static boolean shouldReconnectBeforeFirstFrame(long playerStartedAtMs,
                                                   long lastNetworkDataAtMs,
                                                   long nowMs) {
        if (playerStartedAtMs <= 0 || nowMs < playerStartedAtMs) return false;
        long startupAge = nowMs - playerStartedAtMs;
        if (lastNetworkDataAtMs <= 0) {
            return startupAge >= FIRST_FRAME_NO_DATA_TIMEOUT_MS;
        }
        if (nowMs < lastNetworkDataAtMs) return false;
        return startupAge >= FIRST_FRAME_MAX_WAIT_MS
                || nowMs - lastNetworkDataAtMs >= FIRST_FRAME_DATA_IDLE_TIMEOUT_MS;
    }

    static boolean isBackwardTimelineJump(long oldPositionMs, long newPositionMs) {
        return oldPositionMs >= 0 && newPositionMs >= 0
                && newPositionMs + BACKWARD_TIMELINE_TOLERANCE_MS < oldPositionMs;
    }

    static boolean shouldHardResyncLiveOffset(long liveOffsetMs) {
        return liveOffsetMs != C.TIME_UNSET
                && liveOffsetMs >= LIVE_HARD_RESYNC_OFFSET_MS;
    }

    static int maxLiveAudioQueueBytes(int sampleRate, int channelCount,
                                      int bytesPerSample) {
        long bytes = (long) Math.max(1, sampleRate) * Math.max(1, channelCount)
                * Math.max(1, bytesPerSample) * LIVE_AUDIO_QUEUE_MS / 1_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, bytes));
    }

    static int estimateRenderedFps(int frameCount, long elapsedMs) {
        if (frameCount < 2 || elapsedMs <= 0) return 0;
        return Math.max(1, Math.round((frameCount - 1) * 1_000f / elapsedMs));
    }

    private static String playbackStateName(int state) {
        if (state == Player.STATE_IDLE) return "IDLE";
        if (state == Player.STATE_BUFFERING) return "BUFFERING";
        if (state == Player.STATE_READY) return "READY";
        if (state == Player.STATE_ENDED) return "ENDED";
        return String.valueOf(state);
    }

    private static final class PendingDecodedAudio {
        final byte[] pcm;
        final long sourcePresentationTimeUs;

        PendingDecodedAudio(byte[] pcm, long sourcePresentationTimeUs) {
            this.pcm = pcm;
            this.sourcePresentationTimeUs = sourcePresentationTimeUs;
        }
    }

    private static String codecLabel(String mime, boolean httpFlv) {
        String protocol = httpFlv ? "HTTP-FLV" : "RTMP";
        if ("video/hevc".equals(mime)) return protocol + " / H.265";
        if ("video/avc".equals(mime)) return protocol + " / H.264";
        return mime == null || mime.trim().isEmpty()
                ? protocol : protocol + " / " + mime;
    }

    private static String readable(Throwable error) {
        if (error == null) return "网络流连接失败";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
