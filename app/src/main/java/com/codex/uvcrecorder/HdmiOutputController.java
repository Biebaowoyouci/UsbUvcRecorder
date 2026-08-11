package com.codex.uvcrecorder;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.hardware.display.DisplayManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;

/** Sends either the live UVC texture or a selected video file to an Android presentation display. */
final class HdmiOutputController implements DisplayManager.DisplayListener {
    private static final String TAG = "HdmiOutputController";

    enum Mode { NONE, LIVE, FILE }

    interface Listener {
        void onOutputStateChanged(Mode mode, String displayName);

        void onOutputWarning(String message);
    }

    private final Activity activity;
    private final DisplayManager displayManager;
    private final AudioManager audioManager;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Mode mode = Mode.NONE;
    private OutputPresentation presentation;
    private UvcSurfaceSource liveSource;
    private PcmAudioSubscription audioSubscription;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean audioRunning;

    HdmiOutputController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        displayManager.registerDisplayListener(this, mainHandler);
    }

    Mode getMode() {
        return mode;
    }

    boolean isActive() {
        return mode != Mode.NONE && presentation != null;
    }

    boolean hasExternalDisplay() {
        return findPresentationDisplay() != null;
    }

    boolean startLive(UvcSurfaceSource source) {
        Display display = findPresentationDisplay();
        if (display == null) {
            listener.onOutputWarning("未检测到 HDMI/USB-C 外接显示器");
            return false;
        }
        stop();
        mode = Mode.LIVE;
        liveSource = source;
        showPresentation(display, null);
        notifyState();
        return true;
    }

    boolean startFile(Uri uri) {
        Display display = findPresentationDisplay();
        if (display == null) {
            listener.onOutputWarning("未检测到 HDMI/USB-C 外接显示器");
            return false;
        }
        stop();
        mode = Mode.FILE;
        showPresentation(display, uri);
        notifyState();
        return true;
    }

    void attachLiveSource(UvcSurfaceSource source) {
        if (mode != Mode.LIVE || presentation == null) return;
        detachLiveSource(liveSource);
        liveSource = source;
        Surface surface = presentation.getOutputSurface();
        if (surface != null) attachLiveSurface(surface, presentation.getSurfaceWidth(),
                presentation.getSurfaceHeight());
    }

    void detachLiveSource(UvcSurfaceSource source) {
        if (source == null || liveSource != source) return;
        Surface surface = presentation == null ? null : presentation.getOutputSurface();
        if (surface != null) source.removeDisplaySurface(surface);
        liveSource = null;
        stopLiveAudio();
    }

    void stop() {
        OutputPresentation current = presentation;
        presentation = null;
        UvcSurfaceSource source = liveSource;
        liveSource = null;
        if (source != null && current != null && current.getOutputSurface() != null) {
            source.removeDisplaySurface(current.getOutputSurface());
        }
        stopLiveAudio();
        mode = Mode.NONE;
        if (current != null) {
            current.setOnDismissListener(null);
            current.dismiss();
        }
        notifyState();
    }

    void release() {
        stop();
        displayManager.unregisterDisplayListener(this);
    }

    private void showPresentation(Display display, Uri fileUri) {
        OutputPresentation output = new OutputPresentation(activity, display, fileUri,
                findHdmiAudioOutput(),
                new OutputPresentation.Callback() {
                    @Override
                    public void onSurfaceAvailable(Surface surface, int width, int height) {
                        if (mode != Mode.LIVE) return;
                        attachLiveSurface(surface, width, height);
                    }

                    @Override
                    public void onSurfaceDestroyed(Surface surface) {
                        if (liveSource != null) liveSource.removeDisplaySurface(surface);
                        stopLiveAudio();
                    }

                    @Override
                    public void onPlaybackError(String message) {
                        listener.onOutputWarning(message);
                    }
                });
        presentation = output;
        output.setOnDismissListener(dialog -> {
            if (presentation == output) stop();
        });
        try {
            output.show();
        } catch (Throwable error) {
            presentation = null;
            mode = Mode.NONE;
            listener.onOutputWarning("无法打开外接显示器：" + readableMessage(error));
        }
    }

    private void attachLiveSurface(Surface surface, int width, int height) {
        UvcSurfaceSource source = liveSource;
        if (source == null) return;
        try {
            source.addDisplaySurface(surface, width, height);
            startLiveAudio(source);
        } catch (Throwable error) {
            listener.onOutputWarning("当前信号 HDMI 输出失败：" + readableMessage(error));
        }
    }

    private void startLiveAudio(UvcSurfaceSource source) {
        if (!AppSettings.isUsbAudioEnabled(activity) || audioRunning) return;
        try {
            AudioDeviceInfo hdmiAudioDevice = findHdmiAudioOutput();
            if (hdmiAudioDevice == null) {
                listener.onOutputWarning("视频已输出，但系统没有检测到 HDMI 音频输出端口");
                return;
            }
            PcmAudioSubscription subscription = source.subscribeAudio(activity);
            int channelMask = subscription.channelCount == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuffer = AudioTrack.getMinBufferSize(subscription.sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(Math.max(minBuffer, subscription.bufferSize), 16 * 1024);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(subscription.sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                subscription.close();
                throw new IllegalStateException("HDMI 音频输出初始化失败");
            }
            if (!track.setPreferredDevice(hdmiAudioDevice)) {
                track.release();
                subscription.close();
                throw new IllegalStateException("无法将实时音频路由到 HDMI");
            }
            audioSubscription = subscription;
            audioTrack = track;
            audioRunning = true;
            Log.i(TAG, "Live UAC audio routed to " + describeAudioDevice(hdmiAudioDevice)
                    + ", " + subscription.sampleRate + " Hz, "
                    + subscription.channelCount + " ch");
            track.play();
            audioThread = new Thread(() -> pumpAudio(subscription, track), "hdmi-uac-output");
            audioThread.start();
        } catch (Throwable error) {
            listener.onOutputWarning("视频已输出，但 UAC 音频不可用：" + readableMessage(error));
        }
    }

    private void pumpAudio(PcmAudioSubscription subscription, AudioTrack track) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            while (audioRunning && audioSubscription == subscription) {
                byte[] pcm = subscription.queue.poll(250, TimeUnit.MILLISECONDS);
                if (pcm != null) writePcmFully(track, pcm,
                        subscription.channelCount * subscription.bytesPerSample);
                if (subscription.failure != null) throw subscription.failure;
            }
        } catch (Throwable error) {
            if (audioRunning) mainHandler.post(() -> listener.onOutputWarning(
                    "HDMI UAC 音频已停止：" + readableMessage(error)));
        }
    }

    private void stopLiveAudio() {
        audioRunning = false;
        PcmAudioSubscription subscription = audioSubscription;
        AudioTrack track = audioTrack;
        Thread thread = audioThread;
        audioSubscription = null;
        audioTrack = null;
        audioThread = null;
        if (subscription != null) subscription.close();
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Display findPresentationDisplay() {
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display display : displays) {
            if (display.getState() != Display.STATE_OFF) return display;
        }
        return null;
    }

    private AudioDeviceInfo findHdmiAudioOutput() {
        if (audioManager == null) return null;
        AudioDeviceInfo fallback = null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_HDMI) return device;
            if (type == AudioDeviceInfo.TYPE_HDMI_ARC
                    || type == AudioDeviceInfo.TYPE_HDMI_EARC) {
                fallback = device;
            }
        }
        return fallback;
    }

    private static void writePcmFully(AudioTrack track, byte[] pcm, int frameBytes) {
        int safeFrameBytes = Math.max(1, frameBytes);
        int length = pcm.length - pcm.length % safeFrameBytes;
        int offset = 0;
        while (offset < length) {
            int written = track.write(pcm, offset, length - offset, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("HDMI PCM 写入失败：" + written);
            }
            if (written == 0) continue;
            offset += written;
        }
    }

    private static String describeAudioDevice(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        return (name == null ? "HDMI" : name.toString()) + " [id=" + device.getId()
                + ", type=" + device.getType() + "]";
    }

    private void notifyState() {
        String displayName = presentation == null ? null : presentation.getDisplay().getName();
        listener.onOutputStateChanged(mode, displayName);
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (presentation != null && presentation.getDisplay().getDisplayId() == displayId) {
            listener.onOutputWarning("HDMI 外接显示器已断开");
            stop();
        }
    }

    private static String readableMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "未知错误" : error.getClass().getSimpleName()) : message;
    }

    private static final class OutputPresentation extends Presentation
            implements SurfaceHolder.Callback {
        interface Callback {
            void onSurfaceAvailable(Surface surface, int width, int height);

            void onSurfaceDestroyed(Surface surface);

            void onPlaybackError(String message);
        }

        private final Uri fileUri;
        private final AudioDeviceInfo hdmiAudioDevice;
        private final Callback callback;
        private final Handler playbackHandler = new Handler(Looper.getMainLooper());
        private Surface outputSurface;
        private int surfaceWidth;
        private int surfaceHeight;
        private MediaPlayer player;

        OutputPresentation(Context context, Display display, Uri fileUri,
                           AudioDeviceInfo hdmiAudioDevice, Callback callback) {
            super(context, display);
            this.fileUri = fileUri;
            this.hdmiAudioDevice = hdmiAudioDevice;
            this.callback = callback;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);
            SurfaceView surfaceView = new SurfaceView(getContext());
            root.addView(surfaceView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            surfaceView.getHolder().addCallback(this);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            outputSurface = holder.getSurface();
            surfaceWidth = holder.getSurfaceFrame().width();
            surfaceHeight = holder.getSurfaceFrame().height();
            if (fileUri == null) {
                callback.onSurfaceAvailable(outputSurface, surfaceWidth, surfaceHeight);
            } else {
                startFilePlayback(outputSurface);
            }
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            surfaceWidth = width;
            surfaceHeight = height;
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            Surface old = outputSurface;
            outputSurface = null;
            if (fileUri == null && old != null) callback.onSurfaceDestroyed(old);
            releasePlayer();
        }

        @Override
        protected void onStop() {
            releasePlayer();
            super.onStop();
        }

        Surface getOutputSurface() {
            return outputSurface;
        }

        int getSurfaceWidth() {
            return surfaceWidth;
        }

        int getSurfaceHeight() {
            return surfaceHeight;
        }

        private void startFilePlayback(Surface surface) {
            releasePlayer();
            try {
                MediaPlayer mediaPlayer = new MediaPlayer();
                player = mediaPlayer;
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                if (hdmiAudioDevice == null) {
                    callback.onPlaybackError("视频可以输出，但系统没有检测到 HDMI 音频输出端口");
                } else {
                    routeFileAudio(mediaPlayer, false);
                }
                mediaPlayer.setDataSource(getContext(), fileUri);
                mediaPlayer.setSurface(surface);
                mediaPlayer.setLooping(true);
                mediaPlayer.setOnPreparedListener(mp -> {
                    routeFileAudio(mp, true);
                    mp.start();
                    // Some vendor MediaPlayer implementations create the real AudioTrack only
                    // after start(), which can discard an earlier preferred-device request.
                    playbackHandler.postDelayed(() -> {
                        if (player != mp || !mp.isPlaying()) return;
                        routeFileAudio(mp, true);
                    }, 350);
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    callback.onPlaybackError("视频文件无法通过 HDMI 播放（" + what + "/" + extra + "）");
                    return true;
                });
                mediaPlayer.prepareAsync();
            } catch (Throwable error) {
                callback.onPlaybackError("无法打开视频文件：" + readableMessage(error));
            }
        }

        private void releasePlayer() {
            MediaPlayer current = player;
            player = null;
            playbackHandler.removeCallbacksAndMessages(null);
            if (current != null) {
                try {
                    current.stop();
                } catch (Throwable ignored) {
                }
                current.release();
            }
        }

        private void routeFileAudio(MediaPlayer mediaPlayer, boolean warnOnFailure) {
            if (hdmiAudioDevice == null) return;
            boolean accepted = mediaPlayer.setPreferredDevice(hdmiAudioDevice);
            AudioDeviceInfo routed = mediaPlayer.getRoutedDevice();
            boolean correct = routed == null || routed.getId() == hdmiAudioDevice.getId();
            Log.i(TAG, "File HDMI route request accepted=" + accepted + ", routed="
                    + (routed == null ? "pending" : describeAudioDevice(routed))
                    + ", target=" + describeAudioDevice(hdmiAudioDevice));
            if (warnOnFailure && (!accepted || !correct)) {
                callback.onPlaybackError("文件画面已输出，但系统未能把声音切换到 HDMI");
            }
        }
    }
}
