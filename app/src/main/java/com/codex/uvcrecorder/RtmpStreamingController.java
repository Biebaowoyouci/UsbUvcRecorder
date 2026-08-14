package com.codex.uvcrecorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.pedro.common.AudioCodec;
import com.pedro.common.ConnectChecker;
import com.pedro.common.VideoCodec;
import com.pedro.common.socket.base.SocketType;
import com.pedro.encoder.TimestampMode;
import com.pedro.encoder.input.sources.audio.AudioSource;
import com.pedro.encoder.input.sources.audio.NoAudioSource;
import com.pedro.encoder.utils.ViewPort;
import com.pedro.library.rtmp.RtmpStream;

import java.util.Locale;

/** Owns one RTMP session sourced from the app's current live UVC signal. */
final class RtmpStreamingController {
    private static final String TAG = "RtmpStreaming";
    private static final int SEND_CACHE_FRAMES = 200;
    private static final int WIFI_INTERLEAVE_DELAY_MS = 350;
    private static final int CELLULAR_INTERLEAVE_DELAY_MS = 800;
    private static final int OTHER_INTERLEAVE_DELAY_MS = 500;
    private static final int MIN_ADAPTIVE_VIDEO_BITRATE = 800_000;
    private static final int CACHE_CONGESTION_PERCENT = 20;
    private static final int CACHE_FAST_REDUCE_PERCENT = 50;
    private static final int CACHE_DISCARD_PERCENT = 85;
    private static final int EFFECTIVELY_UNLIMITED_RETRIES = Integer.MAX_VALUE;
    private static final long NETWORK_WAKE_DEBOUNCE_MS = 1_200L;
    private static final long FAST_BITRATE_CHANGE_INTERVAL_MS = 1_000L;
    private static final long ADAPTIVE_MONITOR_INTERVAL_MS = 1_000L;
    private static final int CACHE_EARLY_PRESSURE_PERCENT = 8;
    private static final int CACHE_RISING_ITEMS = 4;
    private static final int STABLE_WINDOWS_BEFORE_INCREASE = 8;

    enum State {
        DISABLED, WAITING, PREPARING, CONNECTING, LIVE, RETRYING, ERROR
    }

    interface Listener {
        void onRtmpState(State state, String detail);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network activeDefaultNetwork;
    private NetworkCapabilities activeNetworkCapabilities;
    private RtmpStream stream;
    private UvcSurfaceSource activeSource;
    private String activeSignature = "";
    private String liveDescription = "";
    private volatile boolean desired;
    private volatile boolean releasing;
    private long sessionGeneration;
    private int consecutiveFailures;
    private boolean retryScheduled;
    private boolean live;
    private boolean networkUnavailable;
    private long lastNetworkWakeMs;
    private int activeInputWidth;
    private int activeInputHeight;
    private int activeFps;
    private String activeSourceLabel = "";
    private int configuredVideoBitrateBps;
    private int activeVideoBitrateBps;
    private long lastBitrateChangeAtMs;
    private long lastDroppedVideoFrames;
    private long latestMeasuredBitrateBps;
    private int previousCacheItems;
    private int stableNetworkWindows;

    private final Runnable adaptiveMonitor = new Runnable() {
        @Override
        public void run() {
            if (!desired || !live || stream == null) return;
            long generation = sessionGeneration;
            updateAdaptiveBitrate(generation, latestMeasuredBitrateBps);
            if (isCurrentSession(generation) && live && stream != null) {
                mainHandler.postDelayed(this, ADAPTIVE_MONITOR_INTERVAL_MS);
            }
        }
    };

    RtmpStreamingController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        connectivityManager = (ConnectivityManager) this.context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    void sync(UvcSurfaceSource source, int inputWidth, int inputHeight, int fps,
              String sourceLabel) {
        if (!AppSettings.isRtmpEnabled(context)) {
            stop();
            notifyState(State.DISABLED, "");
            return;
        }
        String url = AppSettings.getRtmpUrl(context);
        if (!SettingsActivity.isValidRtmpUrl(url)) {
            stop();
            notifyState(State.ERROR, "服务器地址无效，请到设置中修改");
            return;
        }
        if (source == null || inputWidth <= 0 || inputHeight <= 0 || fps <= 0) {
            stop();
            notifyState(State.WAITING, "等待输入信号");
            return;
        }

        int[] output = outputSize(inputWidth, inputHeight,
                AppSettings.getRtmpMaxHeight(context));
        int outputFps = Math.max(1, Math.min(60, fps));
        int bitrateMbps = AppSettings.getRtmpBitrateMbps(context);
        String signature = System.identityHashCode(source) + "|" + output[0] + "x" + output[1]
                + "@" + outputFps + "|" + bitrateMbps + "|"
                + AppSettings.isRtmpAudioEnabled(context)
                + "|" + url;
        if (stream != null && desired && signature.equals(activeSignature)) return;

        stop();
        desired = true;
        activeSource = source;
        activeInputWidth = inputWidth;
        activeInputHeight = inputHeight;
        activeFps = fps;
        activeSourceLabel = sourceLabel == null ? "" : sourceLabel;
        activeSignature = signature;
        final long generation = sessionGeneration;
        configuredVideoBitrateBps = bitrateMbps * 1_000_000;
        NetworkCapabilities capabilities = currentNetworkCapabilities();
        activeVideoBitrateBps = initialVideoBitrateBps(configuredVideoBitrateBps,
                capabilities == null ? 0 : capabilities.getLinkUpstreamBandwidthKbps());
        liveDescription = (sourceLabel == null || sourceLabel.trim().isEmpty()
                ? "" : sourceLabel.trim() + " / ")
                + output[0] + "×" + output[1] + " " + outputFps + "p / "
                + bitrateMbps + " Mbps 自适应";
        if (connectivityManager != null && !areCapabilitiesUsable(capabilities)) {
            networkUnavailable = true;
            notifyState(State.RETRYING, "移动网络不可用，等待恢复");
            return;
        }
        networkUnavailable = false;
        notifyState(State.PREPARING, liveDescription);
        try {
            AudioSource audioSource = new NoAudioSource();
            int audioRate = 48_000;
            boolean stereo = true;
            boolean withAudio = false;
            if (AppSettings.isRtmpAudioEnabled(context)
                    && AppSettings.isUsbAudioEnabled(context)) {
                try {
                    PcmAudioSubscription subscription = source.subscribeAudio(context);
                    audioSource = new UacRtmpAudioSource(subscription);
                    audioRate = subscription.sampleRate;
                    stereo = subscription.channelCount == 2;
                    withAudio = true;
                } catch (Exception error) {
                    notifyState(State.PREPARING, liveDescription + " / UAC 不可用，视频推流");
                }
            }

            RtmpStream next = new RtmpStream(context, new SessionConnectChecker(generation),
                    new UvcRtmpVideoSource(source), audioSource);
            next.setVideoCodec(VideoCodec.H264);
            next.setAudioCodec(AudioCodec.AAC);
            next.setTimestampMode(TimestampMode.CLOCK, TimestampMode.CLOCK);
            next.getStreamClient().setSocketType(SocketType.JAVA);
            next.getStreamClient().setReTries(EFFECTIVELY_UNLIMITED_RETRIES);
            // A dead mobile socket must not hold the session for 15+ seconds
            // after the handset has already recovered 5G connectivity.
            next.getStreamClient().setSocketTimeout(3_000);
            next.getStreamClient().resizeCache(SEND_CACHE_FRAMES);
            next.getStreamClient().setDelay(interleaveDelayMs(
                    capabilities != null && capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_CELLULAR),
                    capabilities != null && capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI)));
            next.getStreamClient().setBitrateExponentialFactor(0.5f);
            next.getStreamClient().setWriteChunkSize(4_096);
            // RootEncoder 2.7.5 launches RTMP ping writes in an unguarded
            // coroutine. When a mobile socket closes, sendPing can throw a
            // SocketException outside ConnectChecker and terminate the app.
            // The 3-second socket timeout, fail-on-read and Android network
            // callback below already provide faster and safe liveness checks.
            next.getStreamClient().shouldSendPings(false);
            next.getStreamClient().shouldFailOnRead(true);
            boolean videoReady = next.prepareVideo(output[0], output[1],
                    activeVideoBitrateBps, outputFps, 2);
            if (videoReady) {
                // The app's UVC/router branch has already rotated and
                // center-cropped into a SurfaceTexture with the exact encoder
                // dimensions. RootEncoder's automatic encoder viewport can
                // still use the Activity orientation and reduce a 1080x1920
                // stream to a 1080x607 strip. Force a one-to-one final blit.
                next.getGlInterface().setCameraOrientation(0);
                next.getGlInterface().setStreamIsPortrait(output[1] > output[0]);
                next.getGlInterface().setStreamViewPort(
                        new ViewPort(0, 0, output[0], output[1]));
            }
            boolean audioReady = next.prepareAudio(audioRate, stereo,
                    stereo ? 160_000 : 128_000);
            if (!videoReady || !audioReady) {
                next.release();
                throw new IllegalStateException("手机硬件编码器不支持该推流参数");
            }
            stream = next;
            resetAdaptiveController();
            if (!withAudio) liveDescription += " / 无音频";
            notifyState(State.CONNECTING, liveDescription);
            next.startStream(url);
        } catch (Throwable error) {
            desired = false;
            releaseStream();
            notifyState(State.ERROR, readable(error));
        }
    }

    void stop() {
        desired = false;
        sessionGeneration++;
        consecutiveFailures = 0;
        retryScheduled = false;
        live = false;
        networkUnavailable = false;
        activeSource = null;
        activeInputWidth = 0;
        activeInputHeight = 0;
        activeFps = 0;
        activeSourceLabel = "";
        configuredVideoBitrateBps = 0;
        activeVideoBitrateBps = 0;
        lastBitrateChangeAtMs = 0;
        lastDroppedVideoFrames = 0;
        latestMeasuredBitrateBps = 0;
        previousCacheItems = 0;
        stableNetworkWindows = 0;
        mainHandler.removeCallbacks(adaptiveMonitor);
        activeSignature = "";
        liveDescription = "";
        releaseStream();
    }

    void release() {
        stop();
        unregisterNetworkCallback();
        mainHandler.removeCallbacksAndMessages(null);
    }

    boolean isActive() {
        return desired && stream != null;
    }

    boolean isDesired() {
        return desired;
    }

    private void resetAdaptiveController() {
        lastBitrateChangeAtMs = SystemClock.elapsedRealtime();
        lastDroppedVideoFrames = 0;
        latestMeasuredBitrateBps = 0;
        previousCacheItems = 0;
        stableNetworkWindows = 0;
    }

    private void applyAdaptiveBitrate(long generation, int requestedBitrateBps) {
        if (!isCurrentSession(generation)) return;
        RtmpStream current = stream;
        if (current == null || configuredVideoBitrateBps <= 0) return;
        int floor = Math.min(configuredVideoBitrateBps, MIN_ADAPTIVE_VIDEO_BITRATE);
        int safeBitrate = Math.max(floor,
                Math.min(configuredVideoBitrateBps, requestedBitrateBps));
        if (safeBitrate == activeVideoBitrateBps) return;
        try {
            current.setVideoBitrateOnFly(safeBitrate);
            activeVideoBitrateBps = safeBitrate;
            lastBitrateChangeAtMs = SystemClock.elapsedRealtime();
            if (safeBitrate < configuredVideoBitrateBps) {
                current.requestKeyframe();
            }
            Log.i(TAG, "encoder bitrate changed to " + safeBitrate);
        } catch (Throwable error) {
            Log.w(TAG, "encoder rejected dynamic bitrate " + safeBitrate, error);
        }
    }

    static int initialVideoBitrateBps(int configuredBitrateBps,
                                      int upstreamBandwidthKbps) {
        if (configuredBitrateBps <= 0) return MIN_ADAPTIVE_VIDEO_BITRATE;
        int floor = Math.min(configuredBitrateBps, MIN_ADAPTIVE_VIDEO_BITRATE);
        if (upstreamBandwidthKbps <= 0) return configuredBitrateBps;
        long networkBudget = upstreamBandwidthKbps * 1_000L * 65L / 100L;
        return (int) Math.max(floor,
                Math.min((long) configuredBitrateBps, networkBudget));
    }

    static int emergencyVideoBitrateBps(int configuredBitrateBps,
                                        int currentBitrateBps) {
        int floor = Math.min(Math.max(1, configuredBitrateBps),
                MIN_ADAPTIVE_VIDEO_BITRATE);
        int current = currentBitrateBps > 0
                ? currentBitrateBps : configuredBitrateBps;
        return Math.max(floor, (int) (current * 0.7f));
    }

    static int interleaveDelayMs(boolean cellular, boolean wifi) {
        if (cellular) return CELLULAR_INTERLEAVE_DELAY_MS;
        if (wifi) return WIFI_INTERLEAVE_DELAY_MS;
        return OTHER_INTERLEAVE_DELAY_MS;
    }

    private void releaseStream() {
        RtmpStream current = stream;
        stream = null;
        mainHandler.removeCallbacks(adaptiveMonitor);
        if (current == null) return;
        releasing = true;
        try {
            current.release();
        } catch (Throwable ignored) {
        } finally {
            releasing = false;
        }
    }

    static int[] outputSize(int width, int height, int maxHeight) {
        int shortEdge = Math.min(width, height);
        if (maxHeight <= 0 || shortEdge <= maxHeight) {
            return new int[]{even(width), even(height)};
        }
        double scale = maxHeight / (double) shortEdge;
        return new int[]{even((int) Math.round(width * scale)),
                even((int) Math.round(height * scale))};
    }

    private static int even(int value) {
        return Math.max(2, value - Math.abs(value % 2));
    }

    private void notifyState(State state, String detail) {
        mainHandler.post(() -> listener.onRtmpState(state, detail == null ? "" : detail));
    }

    private void notifyState(long generation, State state, String detail) {
        mainHandler.post(() -> {
            if (isCurrentSession(generation)) {
                listener.onRtmpState(state, detail == null ? "" : detail);
            }
        });
    }

    private boolean isCurrentSession(long generation) {
        return desired && generation == sessionGeneration;
    }

    private void onConnectionStarted(long generation, String url) {
        if (!isCurrentSession(generation)) return;
        retryScheduled = false;
        live = false;
        notifyState(generation, State.CONNECTING, liveDescription);
    }

    private void onConnectionSuccess(long generation) {
        if (!isCurrentSession(generation)) return;
        consecutiveFailures = 0;
        retryScheduled = false;
        live = true;
        networkUnavailable = false;
        RtmpStream current = stream;
        if (current != null) {
            try {
                current.getStreamClient().setReTries(EFFECTIVELY_UNLIMITED_RETRIES);
                current.getStreamClient().resetDroppedAudioFrames();
                current.getStreamClient().resetDroppedVideoFrames();
                current.getStreamClient().resetBytesSend();
                lastDroppedVideoFrames = 0;
                latestMeasuredBitrateBps = 0;
                previousCacheItems = 0;
                stableNetworkWindows = 0;
                current.requestKeyframe();
            } catch (Exception ignored) {
            }
        }
        mainHandler.removeCallbacks(adaptiveMonitor);
        mainHandler.postDelayed(adaptiveMonitor, ADAPTIVE_MONITOR_INTERVAL_MS);
        notifyState(generation, State.LIVE, liveDescription);
    }

    private void onNewBitrate(long generation, long bitrate) {
        if (!isCurrentSession(generation)) return;
        latestMeasuredBitrateBps = Math.max(0, bitrate);
        String rate = updateAdaptiveBitrate(generation, bitrate);
        notifyState(generation, State.LIVE, liveDescription + " / 上传 " + rate);
    }

    private String updateAdaptiveBitrate(long generation, long measuredBitrate) {
        RtmpStream current = stream;
        if (current == null) return formatMbps(measuredBitrate);
        int cacheItems = 0;
        int cacheSize = SEND_CACHE_FRAMES;
        boolean congested = false;
        long droppedVideo = lastDroppedVideoFrames;
        try {
            cacheItems = current.getStreamClient().getItemsInCache();
            cacheSize = Math.max(1, current.getStreamClient().getCacheSize());
            congested = current.getStreamClient().hasCongestion(
                    CACHE_CONGESTION_PERCENT);
            droppedVideo = current.getStreamClient().getDroppedVideoFrames();
        } catch (Throwable ignored) {
        }

        int usedPercent = cacheItems * 100 / Math.max(1, cacheSize);
        long now = SystemClock.elapsedRealtime();
        boolean dropped = droppedVideo > lastDroppedVideoFrames;
        boolean cacheRising = cacheItems >= previousCacheItems + CACHE_RISING_ITEMS;
        boolean measuredTooLow = measuredBitrate > 0 && activeVideoBitrateBps > 0
                && measuredBitrate * 100L < activeVideoBitrateBps * 85L;
        boolean pressure = shouldReduceBitrate(cacheItems, cacheSize,
                previousCacheItems, dropped, measuredBitrate, activeVideoBitrateBps);
        if (pressure
                && now - lastBitrateChangeAtMs >= FAST_BITRATE_CHANGE_INTERVAL_MS) {
            int target = congestionTargetBitrateBps(configuredVideoBitrateBps,
                    activeVideoBitrateBps, measuredBitrate);
            applyAdaptiveBitrate(generation, target);
            stableNetworkWindows = 0;
        } else {
            boolean stable = cacheItems == 0 && !congested && !cacheRising
                    && !measuredTooLow && !dropped;
            stableNetworkWindows = stable ? stableNetworkWindows + 1 : 0;
            if (stableNetworkWindows >= STABLE_WINDOWS_BEFORE_INCREASE
                    && activeVideoBitrateBps < configuredVideoBitrateBps
                    && now - lastBitrateChangeAtMs >= STABLE_WINDOWS_BEFORE_INCREASE
                    * ADAPTIVE_MONITOR_INTERVAL_MS) {
                int increased = Math.min(configuredVideoBitrateBps,
                        Math.max(activeVideoBitrateBps + 100_000,
                                (int) (activeVideoBitrateBps * 1.08f)));
                NetworkCapabilities capabilities = currentNetworkCapabilities();
                int networkLimit = capabilities == null ? configuredVideoBitrateBps
                        : initialVideoBitrateBps(configuredVideoBitrateBps,
                        capabilities.getLinkUpstreamBandwidthKbps());
                applyAdaptiveBitrate(generation, Math.min(increased, networkLimit));
                stableNetworkWindows = 0;
            }
        }
        lastDroppedVideoFrames = droppedVideo;
        previousCacheItems = cacheItems;

        if (usedPercent >= CACHE_DISCARD_PERCENT
                || cacheItems >= Math.max(24, cacheSize * CACHE_FAST_REDUCE_PERCENT / 100)) {
            try {
                // Keep the mobile jitter buffer for short variations, but
                // never let it turn into seconds of stale live video.
                current.getStreamClient().clearCache();
                current.requestKeyframe();
                cacheItems = 0;
            } catch (Throwable ignored) {
            }
        }
        return formatMbps(measuredBitrate) + " / 编码 "
                + formatMbps(activeVideoBitrateBps) + " / 缓存 "
                + cacheItems + "/" + cacheSize;
    }

    static boolean shouldReduceBitrate(int cacheItems, int cacheSize,
                                       int previousCacheItems, boolean droppedVideo,
                                       long measuredBitrateBps, int activeVideoBitrateBps) {
        int safeSize = Math.max(1, cacheSize);
        int usedPercent = Math.max(0, cacheItems) * 100 / safeSize;
        boolean cacheRising = cacheItems >= previousCacheItems + CACHE_RISING_ITEMS;
        boolean throughputDeficit = cacheItems > 0 && measuredBitrateBps > 0
                && activeVideoBitrateBps > 0
                && measuredBitrateBps * 100L < activeVideoBitrateBps * 85L;
        return droppedVideo || usedPercent >= CACHE_EARLY_PRESSURE_PERCENT
                || cacheRising || throughputDeficit;
    }

    static int congestionTargetBitrateBps(int configuredBitrateBps,
                                          int currentBitrateBps,
                                          long measuredBitrateBps) {
        int emergency = emergencyVideoBitrateBps(configuredBitrateBps,
                currentBitrateBps);
        if (measuredBitrateBps <= 0) return emergency;
        long measuredBudget = measuredBitrateBps * 75L / 100L;
        int floor = Math.min(Math.max(1, configuredBitrateBps),
                MIN_ADAPTIVE_VIDEO_BITRATE);
        return Math.max(floor, (int) Math.min(emergency, measuredBudget));
    }

    private static String formatMbps(long bitrate) {
        return String.format(Locale.getDefault(), "%.1f Mbps", bitrate / 1_000_000f);
    }

    private void onConnectionFailed(long generation, String reason) {
        if (!isCurrentSession(generation) || retryScheduled) return;
        live = false;
        if (releasing || stream == null) return;
        consecutiveFailures++;
        if (requestImmediateRetry(generation, reason)) return;
        retryScheduled = true;
        notifyState(generation, State.RETRYING,
                "连接线程已重置，正在重建推流 / " + readable(reason));
        mainHandler.postDelayed(() -> {
            if (!isCurrentSession(generation)) return;
            retryScheduled = false;
            rebuildSession(generation);
        }, 150L);
    }

    private void onDisconnect(long generation) {
        if (!isCurrentSession(generation) || releasing || retryScheduled) return;
        live = false;
        consecutiveFailures++;
        retryScheduled = true;
        notifyState(generation, State.RETRYING,
                "服务器已断开，立即重建推流");
        mainHandler.post(() -> rebuildSession(generation));
    }

    private void onAuthError(long generation) {
        failSession(generation, "服务器鉴权失败，请检查地址和推流密钥");
    }

    private void onAuthSuccess(long generation) {
        if (!isCurrentSession(generation)) return;
        notifyState(generation, State.CONNECTING, liveDescription + " / 鉴权成功");
    }

    private void rebuildSession(long generation) {
        if (!isCurrentSession(generation)) return;
        UvcSurfaceSource source = activeSource;
        int width = activeInputWidth;
        int height = activeInputHeight;
        int fps = activeFps;
        String label = activeSourceLabel;
        sessionGeneration++;
        releaseStream();
        activeSignature = "";
        sync(source, width, height, fps, label);
    }

    private void failSession(long generation, String detail) {
        mainHandler.post(() -> {
            if (!isCurrentSession(generation)) return;
            desired = false;
            sessionGeneration++;
            retryScheduled = false;
            live = false;
            releaseStream();
            listener.onRtmpState(State.ERROR, detail);
        });
    }

    static long retryDelayMs(int failureCount) {
        if (failureCount <= 1) return 0L;
        return Math.min(1_500L, (failureCount - 1L) * 500L);
    }

    private boolean requestImmediateRetry(long generation, String reason) {
        if (!isCurrentSession(generation) || retryScheduled) return false;
        RtmpStream current = stream;
        if (releasing || current == null) return false;
        try {
            // Do not send stale buffered frames after the network comes back.
            current.getStreamClient().clearCache();
            applyAdaptiveBitrate(generation,
                    emergencyVideoBitrateBps(configuredVideoBitrateBps,
                            activeVideoBitrateBps));
        } catch (Throwable ignored) {
        }
        // RootEncoder 2.7.5 can leave reTry() waiting forever in
        // RtmpSender.stop(false) after a mobile Broken pipe. Rebuild the
        // complete client with our own state machine instead.
        retryScheduled = true;
        notifyState(generation, State.RETRYING,
                "第 " + consecutiveFailures + " 次重连，立即重建连接 / "
                        + readable(reason));
        mainHandler.postDelayed(() -> {
            if (!isCurrentSession(generation)) return;
            if (!hasUsableNetwork()) {
                networkUnavailable = true;
                retryScheduled = false;
                releaseStream();
                notifyState(generation, State.RETRYING,
                        "移动网络不可用，等待恢复");
                return;
            }
            retryScheduled = false;
            rebuildSession(generation);
        }, retryDelayMs(consecutiveFailures));
        return true;
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || networkCallback != null) return;
        ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        mainHandler.post(() -> {
                            if (!network.equals(activeDefaultNetwork)) {
                                activeNetworkCapabilities = null;
                            }
                            activeDefaultNetwork = network;
                            handleNetworkChanged();
                        });
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network,
                                                      NetworkCapabilities capabilities) {
                        NetworkCapabilities snapshot =
                                new NetworkCapabilities(capabilities);
                        mainHandler.post(() -> {
                            activeDefaultNetwork = network;
                            activeNetworkCapabilities = snapshot;
                            handleNetworkChanged();
                        });
                    }

                    @Override
                    public void onLost(Network network) {
                        mainHandler.post(() -> {
                            if (network.equals(activeDefaultNetwork)) {
                                activeDefaultNetwork = null;
                                activeNetworkCapabilities = null;
                            }
                            handleNetworkChanged();
                        });
                    }
                };
        try {
            connectivityManager.registerDefaultNetworkCallback(callback);
            networkCallback = callback;
        } catch (RuntimeException ignored) {
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        activeDefaultNetwork = null;
        activeNetworkCapabilities = null;
        if (connectivityManager == null || callback == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
        }
    }

    private void handleNetworkChanged() {
        if (!desired) return;
        boolean available = hasUsableNetwork();
        if (!available) {
            networkUnavailable = true;
            live = false;
            RtmpStream unavailableStream = stream;
            if (unavailableStream != null) {
                try {
                    unavailableStream.getStreamClient().clearCache();
                } catch (Throwable ignored) {
                }
                releaseStream();
            }
            retryScheduled = false;
            notifyState(sessionGeneration, State.RETRYING, "移动网络不可用，等待恢复");
            return;
        }

        boolean recovered = networkUnavailable;
        networkUnavailable = false;
        constrainBitrateToNetwork(sessionGeneration, currentNetworkCapabilities());
        if (live || (!recovered && !retryScheduled && stream != null)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNetworkWakeMs < NETWORK_WAKE_DEBOUNCE_MS) return;
        lastNetworkWakeMs = now;
        long generation = sessionGeneration;
        notifyState(generation, State.RETRYING, "网络已恢复，立即重新连接");
        rebuildSession(generation);
    }

    private boolean hasUsableNetwork() {
        NetworkCapabilities capabilities = currentNetworkCapabilities();
        if (capabilities == null) return connectivityManager == null;
        return areCapabilitiesUsable(capabilities);
    }

    private static boolean areCapabilitiesUsable(NetworkCapabilities capabilities) {
        if (capabilities == null) return false;
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
            return false;
        }
        // A local RTMP server on Wi-Fi does not require Android's public
        // Internet validation. Cellular publishing does.
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private NetworkCapabilities currentNetworkCapabilities() {
        if (connectivityManager == null) return null;
        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities =
                    active == null ? null : connectivityManager.getNetworkCapabilities(active);
            activeDefaultNetwork = active;
            activeNetworkCapabilities = capabilities == null
                    ? null : new NetworkCapabilities(capabilities);
            return activeNetworkCapabilities;
        } catch (RuntimeException ignored) {
            return activeNetworkCapabilities;
        }
    }

    private void constrainBitrateToNetwork(long generation,
                                           NetworkCapabilities capabilities) {
        if (capabilities == null || activeVideoBitrateBps <= 0) return;
        int target = initialVideoBitrateBps(configuredVideoBitrateBps,
                capabilities.getLinkUpstreamBandwidthKbps());
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)) {
            target = Math.min(target, emergencyVideoBitrateBps(
                    configuredVideoBitrateBps, activeVideoBitrateBps));
        }
        if (target < activeVideoBitrateBps) {
            applyAdaptiveBitrate(generation, target);
        }
    }

    private final class SessionConnectChecker implements ConnectChecker {
        private final long generation;

        SessionConnectChecker(long generation) {
            this.generation = generation;
        }

        @Override
        public void onConnectionStarted(String url) {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onConnectionStarted(generation, url));
        }

        @Override
        public void onConnectionSuccess() {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onConnectionSuccess(generation));
        }

        @Override
        public void onNewBitrate(long bitrate) {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onNewBitrate(generation, bitrate));
        }

        @Override
        public void onConnectionFailed(String reason) {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onConnectionFailed(generation, reason));
        }

        @Override
        public void onDisconnect() {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onDisconnect(generation));
        }

        @Override
        public void onAuthError() {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onAuthError(generation));
        }

        @Override
        public void onAuthSuccess() {
            mainHandler.post(() ->
                    RtmpStreamingController.this.onAuthSuccess(generation));
        }
    }

    private static String readable(Throwable error) {
        if (error == null) return "未知错误";
        return readable(error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage());
    }

    private static String readable(String text) {
        if (text == null || text.trim().isEmpty()) return "连接失败";
        String value = text.trim();
        return value.length() > 140 ? value.substring(0, 140) : value;
    }
}
