package com.codex.uvcrecorder;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.serenegiant.usb.Format;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class MultiDeviceController {
    private static final float[] BANDWIDTH_FACTORS = {1.00f, 0.75f, 0.50f, 0.35f};
    private static final int DIRECT_FRAME_TIMEOUT_MS = 5_000;
    private static final int TEXTURE_FRAME_TIMEOUT_MS = 5_000;
    private static final int DEVICE_START_GAP_MS = 1_100;
    private static final int HEALTH_CHECK_MS = 2_500;
    private static final int FRAME_STALL_MS = 7_000;

    interface Listener {
        void onMultiReadinessChanged(int readyCount, int totalCount);

        void onMultiRecordingState(boolean active, long durationMs);

        void onMultiRecordingSaved(String deviceLabel, String displayName);

        void onMultiAudioLevel(float normalized, float db, String sourceLabel);

        void onMultiAudioUnavailable();

        void onMultiWarning(String deviceLabel, String message);

        void onMultiError(String deviceLabel, String message, Throwable error);
    }

    private final AppCompatActivity activity;
    private final GridLayout grid;
    private final Listener listener;
    private final List<Session> sessions = new ArrayList<>();
    private final Map<String, AudioAssignment> audioAssignments = new HashMap<>();
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private final MultiAudioRouter audioRouter;
    private final AudioManager audioManager;
    private final AudioDeviceCallback audioDeviceCallback;
    private boolean released;
    private int recordingRequest;
    private boolean recordingDesired;
    private boolean metersVisible;
    private int outputRotation;
    private int previewDisplayCompensation;
    private String audioConfigurationSignature = "";

    MultiDeviceController(AppCompatActivity activity, GridLayout grid,
                          List<UsbDevice> devices, Listener listener) {
        this.activity = activity;
        this.grid = grid;
        this.listener = listener;
        audioManager = (AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                scheduleAudioDeviceRefresh();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                scheduleAudioDeviceRefresh();
            }
        };
        audioRouter = new MultiAudioRouter(activity, new MultiAudioRouter.Listener() {
            @Override
            public void onLevel(float normalized, float db, String sourceLabel) {
                if (released) return;
                for (Session session : sessions) {
                    session.setSharedAudioLevel(normalized, db, sourceLabel);
                }
                listener.onMultiAudioLevel(normalized, db, sourceLabel);
            }

            @Override
            public void onUnavailable() {
                if (released) return;
                for (Session session : sessions) session.setSharedAudioUnavailable();
                listener.onMultiAudioUnavailable();
            }
        });
        grid.removeAllViews();
        grid.setVisibility(View.VISIBLE);
        for (int i = 0; i < devices.size(); i++) {
            sessions.add(new Session(devices.get(i), i));
        }
        refreshIndependentAudioAssignments();
        layoutTiles();
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            startupHandler.postDelayed(session::start, (long) i * DEVICE_START_GAP_MS);
        }
        audioConfigurationSignature = currentAudioConfigurationSignature();
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, startupHandler);
        }
        notifyReadiness();
    }

    boolean isRecording() {
        if (recordingDesired) return true;
        for (Session session : sessions) {
            if (session.recorder != null && session.recorder.isAnythingActive()) return true;
        }
        return false;
    }

    boolean isReady() {
        return !sessions.isEmpty() && readyCount() == sessions.size();
    }

    int size() {
        return sessions.size();
    }

    void setMetersVisible(boolean visible) {
        metersVisible = visible;
        for (Session session : sessions) session.refreshAudioMonitor(false);
    }

    void setOutputRotation(int degrees) {
        outputRotation = AppSettings.normalizeRotation(degrees);
        for (Session session : sessions) session.setOutputRotation(outputRotation);
    }

    void setPreviewDisplayCompensation(int degrees) {
        previewDisplayCompensation = AppSettings.normalizeRotation(degrees);
        for (Session session : sessions) {
            session.setPreviewDisplayCompensation(previewDisplayCompensation);
        }
    }

    void refreshAudioConfiguration() {
        if (released || isRecording()) return;
        refreshIndependentAudioAssignments();
        String next = currentAudioConfigurationSignature();
        refreshAudioSources();
        if (next.equals(audioConfigurationSignature)) {
            for (Session session : sessions) session.refreshAudioMonitor(false);
            return;
        }
        audioConfigurationSignature = next;
        for (Session session : sessions) {
            session.recreateRecorder();
            session.refreshAudioMonitor(true);
        }
        notifyReadiness();
    }

    private void scheduleAudioDeviceRefresh() {
        startupHandler.removeCallbacks(audioDeviceRefresh);
        startupHandler.postDelayed(audioDeviceRefresh, 350);
    }

    private final Runnable audioDeviceRefresh = () -> {
        if (!released) refreshAudioConfiguration();
    };

    String audioModeLabel() {
        if (!AppSettings.isUsbAudioEnabled(activity)) return "无音频";
        if (!AppSettings.isMultiAudioShareEnabled(activity)) return "每路独立 UAC";
        String label = AppSettings.getMultiAudioSourceLabel(activity);
        return label.isEmpty() ? "共享音源未选择" : "共享音源：" + label;
    }

    boolean isPointOnVideo(float rawX, float rawY) {
        for (Session session : sessions) {
            if (session.isPointOnVideo(rawX, rawY)) return true;
        }
        return false;
    }

    void toggleRecording() {
        if (recordingDesired) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    void startRecording() {
        if (!isReady()) {
            listener.onMultiWarning("多设备", "仍有输入未就绪，等待所有画面稳定后再录制");
            return;
        }
        if (AppSettings.isUsbAudioEnabled(activity)
                && AppSettings.isMultiAudioShareEnabled(activity)
                && AppSettings.getMultiAudioSourceKey(activity).isEmpty()) {
            listener.onMultiWarning("共享音源", "请先在设置中选择要共享的 USB 音频输入");
            return;
        }
        if (AppSettings.isUsbAudioEnabled(activity)
                && AppSettings.isMultiAudioShareEnabled(activity)
                && selectedSharedAudioAssignment() == null) {
            listener.onMultiWarning("共享音源", "所选设备当前没有可用的 UAC 音频输入");
            return;
        }
        recordingDesired = true;
        int request = ++recordingRequest;
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            startupHandler.postDelayed(() -> {
                if (!released && request == recordingRequest
                        && session.ready && session.recorder != null) {
                    session.recorder.startMain();
                    notifyRecordingState();
                }
            }, i * 160L);
        }
        notifyRecordingState();
    }

    void stopRecording() {
        recordingDesired = false;
        recordingRequest++;
        for (Session session : sessions) {
            if (session.recorder != null) session.recorder.stopMain();
        }
        notifyRecordingState();
    }

    List<LiveSource> readyLiveSources() {
        List<LiveSource> result = new ArrayList<>();
        for (Session session : sessions) {
            if (session.ready && session.source != null) {
                int width = session.signal == null ? 1280 : session.signal.width;
                int height = session.signal == null ? 720 : session.signal.height;
                int fps = session.signal == null ? 30 : session.signal.fps;
                result.add(new LiveSource(session.shortLabel, session.source,
                        width, height, fps));
            }
        }
        return result;
    }

    void release() {
        if (released) return;
        released = true;
        recordingDesired = false;
        recordingRequest++;
        startupHandler.removeCallbacksAndMessages(null);
        if (audioManager != null) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        audioRouter.release();
        for (Session session : new ArrayList<>(sessions)) session.release();
        sessions.clear();
        grid.removeAllViews();
        grid.setVisibility(View.GONE);
        notifyReadiness();
        listener.onMultiRecordingState(false, 0);
    }

    private void layoutTiles() {
        int count = Math.max(1, sessions.size());
        int columns = count == 1 ? 1 : 2;
        int rows = (count + columns - 1) / columns;
        grid.setColumnCount(columns);
        grid.setRowCount(rows);
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            int row = i / columns;
            int column = i % columns;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(row, 1, 1f), GridLayout.spec(column, 1, 1f));
            params.width = 0;
            params.height = 0;
            int margin = dp(1);
            params.setMargins(margin, margin, margin, margin);
            session.tile.setLayoutParams(params);
        }
    }

    private int readyCount() {
        int ready = 0;
        for (Session session : sessions) if (session.ready) ready++;
        return ready;
    }

    private void notifyReadiness() {
        refreshAudioSources();
        listener.onMultiReadinessChanged(readyCount(), sessions.size());
    }

    private void notifyRecordingState() {
        boolean active = recordingDesired;
        long duration = 0;
        for (Session session : sessions) {
            active |= session.recordingActive;
            duration = Math.max(duration, session.recordingDuration);
        }
        listener.onMultiRecordingState(active, duration);
    }

    private void refreshAudioSources() {
        List<MultiAudioRouter.Source> ready = new ArrayList<>();
        if (AppSettings.isUsbAudioEnabled(activity)
                && AppSettings.isMultiAudioShareEnabled(activity)) {
            String sourceKey = AppSettings.getMultiAudioSourceKey(activity);
            String sourceLabel = AppSettings.getMultiAudioSourceLabel(activity);
            AudioAssignment assignment = selectedSharedAudioAssignment();
            if (!sourceKey.isEmpty() && assignment != null) {
                ready.add(new MultiAudioRouter.Source(sourceKey + ":" + assignment.deviceId,
                        sourceLabel, assignment.productName, assignment.deviceId));
            }
        }
        audioRouter.setSources(ready);
    }

    private String currentAudioConfigurationSignature() {
        StringBuilder result = new StringBuilder();
        result.append(AppSettings.isUsbAudioEnabled(activity)).append(':')
                .append(AppSettings.isMultiAudioShareEnabled(activity)).append(':')
                .append(AppSettings.getMultiAudioSourceKey(activity));
        for (UsbDevice videoDevice : UsbDeviceCatalog.listVideoInputs(activity)) {
            AudioAssignment assignment = audioAssignments.get(
                    UsbDeviceCatalog.stableKey(videoDevice));
            result.append('|').append(UsbDeviceCatalog.stableKey(videoDevice)).append('=')
                    .append(assignment == null ? -1 : assignment.deviceId);
        }
        return result.toString();
    }

    private void refreshIndependentAudioAssignments() {
        audioAssignments.clear();
        List<UsbDevice> videoDevices = UsbDeviceCatalog.listVideoInputs(activity);
        List<AudioDeviceInfo> available = UsbAudioSource.listUsbInputs(activity);
        Set<Integer> usedIds = new HashSet<>();

        // First reserve confidently name-matched UAC ports for their video devices.
        for (UsbDevice videoDevice : videoDevices) {
            AudioDeviceInfo best = null;
            int bestScore = Integer.MIN_VALUE;
            String videoProduct = videoDevice.getProductName();
            for (AudioDeviceInfo input : available) {
                if (usedIds.contains(input.getId())) continue;
                int score = UsbAudioSource.matchScore(input, videoProduct);
                if (score > bestScore) {
                    best = input;
                    bestScore = score;
                }
            }
            if (best != null && bestScore >= 500) {
                assignAudio(videoDevice, best);
                usedIds.add(best.getId());
            }
        }

        // Composite capture cards often expose unrelated video/audio product strings.
        // Pair the remaining ports in stable display order, never assigning one UAC twice.
        for (UsbDevice videoDevice : videoDevices) {
            if (audioAssignments.containsKey(UsbDeviceCatalog.stableKey(videoDevice))) continue;
            for (AudioDeviceInfo input : available) {
                if (usedIds.add(input.getId())) {
                    assignAudio(videoDevice, input);
                    break;
                }
            }
        }
    }

    private void assignAudio(UsbDevice videoDevice, AudioDeviceInfo input) {
        CharSequence name = input.getProductName();
        String product = name == null || name.toString().trim().isEmpty()
                ? "USB UAC" : name.toString().trim();
        audioAssignments.put(UsbDeviceCatalog.stableKey(videoDevice),
                new AudioAssignment(product, input.getId()));
    }

    private AudioAssignment selectedSharedAudioAssignment() {
        return audioAssignments.get(AppSettings.getMultiAudioSourceKey(activity));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static final class LiveSource {
        final String label;
        final UvcSurfaceSource source;
        final int width;
        final int height;
        final int fps;

        LiveSource(String label, UvcSurfaceSource source, int width, int height, int fps) {
            this.label = label;
            this.source = source;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    private static final class AudioAssignment {
        final String productName;
        final int deviceId;

        AudioAssignment(String productName, int deviceId) {
            this.productName = productName;
            this.deviceId = deviceId;
        }
    }

    private final class Session implements DirectUvcCameraSource.Listener,
            RecordingController.Listener, SurfaceHolder.Callback,
            TextureView.SurfaceTextureListener {
        final UsbDevice device;
        final int index;
        final String shortLabel;
        final String fileTag;
        final FrameLayout tile;
        final AspectRatioSurfaceView directView;
        final TextureView textureView;
        final View modeTouchTarget;
        final TextView status;
        final AudioLevelMeterView audioMeter;
        final AudioLevelMonitor audioMonitor;
        final Handler handler = new Handler(Looper.getMainLooper());
        DirectUvcCameraSource source;
        RecordingController recorder;
        Surface textureSurface;
        List<Size> modes = Collections.emptyList();
        List<Size> fallbacks = Collections.emptyList();
        List<Format> formats = Collections.emptyList();
        Size selectedMode;
        SignalInfo signal;
        boolean opened;
        boolean routerReady;
        boolean ready;
        boolean recordingActive;
        long recordingDuration;
        int generation;
        int fallbackIndex;
        int bandwidthIndex;
        int rotation;
        long lastTextureFrameAt;
        boolean sessionReleased;
        String audioMonitorSignature = "";
        final Runnable healthCheck = new Runnable() {
            @Override
            public void run() {
                if (released || sessionReleased) return;
                if (ready && lastTextureFrameAt > 0
                        && SystemClock.elapsedRealtime() - lastTextureFrameAt > FRAME_STALL_MS) {
                    listener.onMultiWarning(shortLabel, "画面停滞，正在只重启该路输入");
                    Size restart = selectedMode == null ? null : selectedMode.clone();
                    if (restart != null && source != null && source.isOpened()) {
                        startMode(restart, true);
                        return;
                    }
                }
                handler.postDelayed(this, HEALTH_CHECK_MS);
            }
        };

        Session(UsbDevice device, int index) {
            this.device = device;
            this.index = index;
            String product = device.getProductName();
            shortLabel = product == null || product.trim().isEmpty()
                    ? "UVC " + (index + 1) : product;
            fileTag = UsbDeviceCatalog.fileTag(device, index);

            tile = new FrameLayout(activity);
            tile.setBackgroundColor(Color.BLACK);
            directView = new AspectRatioSurfaceView(activity);
            directView.setAspectRatio(16, 9);
            FrameLayout.LayoutParams directParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            tile.addView(directView, directParams);
            textureView = new TextureView(activity);
            textureView.setAlpha(0f);
            textureView.setSurfaceTextureListener(this);
            tile.addView(textureView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            modeTouchTarget = new View(activity);
            modeTouchTarget.setClickable(true);
            modeTouchTarget.setFocusable(true);
            modeTouchTarget.setContentDescription(shortLabel + " 输入格式");
            modeTouchTarget.setOnClickListener(v -> showModeDialog());
            tile.addView(modeTouchTarget, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            status = new TextView(activity);
            status.setTextColor(Color.WHITE);
            status.setTextSize(12);
            status.setMaxLines(3);
            status.setBackgroundResource(R.drawable.bg_panel);
            status.setPadding(dp(9), dp(6), dp(9), dp(6));
            FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            statusParams.setMargins(dp(8), dp(8), dp(8), dp(8));
            tile.addView(status, statusParams);
            audioMeter = new AudioLevelMeterView(activity);
            audioMeter.setPanelOpacity(AppSettings.getButtonOpacity(activity));
            audioMeter.setContentDescription(shortLabel + " 音频电平");
            audioMeter.setVisibility(View.GONE);
            FrameLayout.LayoutParams meterParams = new FrameLayout.LayoutParams(
                    dp(50), dp(240),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            meterParams.setMargins(0, 0, dp(8), 0);
            tile.addView(audioMeter, meterParams);
            audioMonitor = new AudioLevelMonitor(activity, new AudioLevelMonitor.Listener() {
                @Override
                public void onLevel(float normalized, float db) {
                    if (!sessionReleased && !isSharedAudioEnabled()) {
                        audioMeter.setLevel(normalized, db);
                    }
                }

                @Override
                public void onUnavailable() {
                    if (!sessionReleased && !isSharedAudioEnabled()) {
                        audioMeter.setUnavailable();
                    }
                }
            });
            tile.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
                updateModeTouchTarget();
                updateAudioMeterSize();
            });
            directView.getHolder().addCallback(this);
            grid.addView(tile);
            setStatus("等待 USB 权限…");
        }

        void start() {
            if (released || sessionReleased) return;
            source = new DirectUvcCameraSource(activity, device.getDeviceId(), this);
            setOutputRotation(outputRotation);
            source.start();
        }

        void release() {
            sessionReleased = true;
            generation++;
            handler.removeCallbacksAndMessages(null);
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            audioMonitor.release();
            if (source != null) {
                source.setScreenSurface(null, 0, 0);
                source.release();
                source = null;
            }
            if (textureSurface != null) {
                textureSurface.release();
                textureSurface = null;
            }
            ready = false;
            opened = false;
        }

        boolean isSharedAudioEnabled() {
            return AppSettings.isUsbAudioEnabled(activity)
                    && AppSettings.isMultiAudioShareEnabled(activity)
                    && !AppSettings.getMultiAudioSourceKey(activity).isEmpty();
        }

        UvcSurfaceSource recordingSource() {
            if (isSharedAudioEnabled()) {
                return new AudioOverrideSurfaceSource(source, audioRouter);
            }
            return independentAudioSource();
        }

        UvcSurfaceSource independentAudioSource() {
            AudioAssignment assignment = audioAssignments.get(UsbDeviceCatalog.stableKey(device));
            if (assignment != null) {
                audioMeter.setContentDescription(shortLabel + " 独立音频："
                        + assignment.productName);
                return new StrictAudioSurfaceSource(source,
                        assignment.productName, assignment.deviceId);
            }
            audioMeter.setContentDescription(shortLabel + " 没有对应的独立 UAC");
            return new StrictAudioSurfaceSource(source,
                    "__unassigned_uac_" + device.getDeviceId());
        }

        void recreateRecorder() {
            if (!ready || source == null || signal == null || isRecording()) return;
            if (recorder != null) recorder.release();
            recorder = new RecordingController(activity, recordingSource(),
                    signal, this, fileTag, false);
        }

        void refreshAudioMonitor(boolean force) {
            boolean enabled = AppSettings.isUsbAudioEnabled(activity) && ready && source != null;
            audioMeter.setPanelOpacity(AppSettings.getButtonOpacity(activity));
            audioMeter.setVisibility(enabled && metersVisible ? View.VISIBLE : View.GONE);
            String next = enabled && metersVisible && !isSharedAudioEnabled()
                    ? UsbDeviceCatalog.stableKey(device) : "";
            if (!force && next.equals(audioMonitorSignature)) return;
            audioMonitorSignature = next;
            audioMonitor.stop();
            audioMeter.setUnavailable();
            if (!next.isEmpty()) {
                audioMonitor.start(independentAudioSource());
            }
        }

        void setSharedAudioLevel(float normalized, float db, String sourceLabel) {
            if (!isSharedAudioEnabled()) return;
            audioMeter.setContentDescription(shortLabel + " 共享音频：" + sourceLabel);
            audioMeter.setLevel(normalized, db);
        }

        void setSharedAudioUnavailable() {
            if (!isSharedAudioEnabled()) return;
            audioMeter.setContentDescription(shortLabel + " 共享音频不可用");
            audioMeter.setUnavailable();
        }

        void setOutputRotation(int degrees) {
            rotation = AppSettings.normalizeRotation(degrees);
            if (source != null) source.setOutputRotation(rotation);
            updateModeTouchTarget();
            updateStatus();
        }

        void setPreviewDisplayCompensation(int degrees) {
            float compensation = AppSettings.normalizeRotation(degrees);
            textureView.setRotation(compensation);
            directView.setRotation(compensation);
        }

        boolean isPointOnVideo(float rawX, float rawY) {
            if (modeTouchTarget.getVisibility() != View.VISIBLE
                    || modeTouchTarget.getWidth() <= 0 || modeTouchTarget.getHeight() <= 0) {
                return false;
            }
            int[] location = new int[2];
            modeTouchTarget.getLocationOnScreen(location);
            return rawX >= location[0] && rawX < location[0] + modeTouchTarget.getWidth()
                    && rawY >= location[1] && rawY < location[1] + modeTouchTarget.getHeight();
        }

        void updateModeTouchTarget() {
            int availableWidth = tile.getWidth();
            int availableHeight = tile.getHeight();
            if (availableWidth <= 0 || availableHeight <= 0) return;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                    modeTouchTarget.getLayoutParams();
            if (params.width != availableWidth || params.height != availableHeight) {
                params.width = availableWidth;
                params.height = availableHeight;
                params.gravity = Gravity.CENTER;
                modeTouchTarget.setLayoutParams(params);
            }
        }

        void updateAudioMeterSize() {
            if (tile.getWidth() <= 0) return;
            int desiredHeight = Math.round(tile.getWidth() * 0.43f);
            desiredHeight = Math.max(dp(160), Math.min(dp(320), desiredHeight));
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                    audioMeter.getLayoutParams();
            if (params.height != desiredHeight) {
                params.height = desiredHeight;
                params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                audioMeter.setLayoutParams(params);
            }
        }

        @Override
        public void onAttach(UsbDevice attached) {
            setStatus("正在打开 USB…");
        }

        @Override
        public void onOpened(UsbDevice openedDevice, List<Size> sizes,
                             List<Format> supportedFormats) {
            if (sessionReleased) return;
            opened = true;
            formats = supportedFormats == null ? Collections.emptyList() : supportedFormats;
            modes = uniqueSizes(sizes);
            if (modes.isEmpty()) {
                setStatus("设备未返回可用视频格式");
                return;
            }
            AppSettings.SavedSignalMode saved = AppSettings.getSignalMode(activity, device);
            fallbacks = buildFallbacks(modes, saved);
            fallbackIndex = 0;
            bandwidthIndex = saved != null && saved.matches(fallbacks.get(0))
                    ? Math.min(Math.max(saved.bandwidthIndex, 0), BANDWIDTH_FACTORS.length - 1)
                    : 0;
            startWhenSurfaceReady(fallbacks.get(0), true);
        }

        @Override
        public void onClosed(UsbDevice closed) {
            markNotReady("视频已关闭");
        }

        @Override
        public void onDetach(UsbDevice detached) {
            markNotReady("设备已断开");
        }

        @Override
        public void onCancel(UsbDevice cancelled) {
            markNotReady("USB 权限已取消");
        }

        @Override
        public void onError(UsbDevice failed, Throwable error) {
            markNotReady("打开失败：" + readable(error));
            listener.onMultiError(shortLabel, readable(error), error);
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            if (opened && selectedMode != null && !ready) startMode(selectedMode, true);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format,
                                   int width, int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        }

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture,
                                              int width, int height) {
            replaceTextureSurface(new Surface(surfaceTexture));
            attachTexture(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture,
                                                int width, int height) {
            attachTexture(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            if (source != null) source.setScreenSurface(null, 0, 0);
            replaceTextureSurface(null);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            lastTextureFrameAt = SystemClock.elapsedRealtime();
            if (!routerReady || ready) return;
            ready = true;
            directView.setVisibility(View.INVISIBLE);
            textureView.setAlpha(1f);
            handler.removeCallbacksAndMessages(null);
            recorder = new RecordingController(activity, recordingSource(),
                    signal, this, fileTag, false);
            AppSettings.saveSignalMode(activity, device, selectedMode, bandwidthIndex);
            updateStatus();
            refreshAudioMonitor(true);
            notifyReadiness();
            handler.removeCallbacks(healthCheck);
            handler.postDelayed(healthCheck, HEALTH_CHECK_MS);
            if (recordingDesired) {
                int request = recordingRequest;
                handler.postDelayed(() -> {
                    if (!released && !sessionReleased && ready && recordingDesired
                            && request == recordingRequest && recorder != null) {
                        recorder.startMain();
                    }
                }, index * 160L);
            }
        }

        void startWhenSurfaceReady(Size mode, boolean allowFallback) {
            selectedMode = mode.clone();
            updateModeTouchTarget();
            if (directView.getHolder().getSurface().isValid()) {
                startMode(mode, allowFallback);
            } else {
                setStatus("等待预览画布…");
            }
        }

        void startMode(Size mode, boolean allowFallback) {
            if (source == null || !source.isOpened()) return;
            selectedMode = mode.clone();
            updateModeTouchTarget();
            directView.setVisibility(View.VISIBLE);
            textureView.setAlpha(0f);
            if (!directView.getHolder().getSurface().isValid()) {
                ready = false;
                routerReady = false;
                setStatus("正在重建预览画布…");
                notifyReadiness();
                return;
            }
            int request = ++generation;
            handler.removeCallbacksAndMessages(null);
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            audioMonitorSignature = "";
            audioMonitor.stop();
            audioMeter.setUnavailable();
            audioMeter.setVisibility(View.GONE);
            ready = false;
            routerReady = false;
            lastTextureFrameAt = 0;
            recordingActive = false;
            signal = SignalInfo.from(mode, formats);
            directView.setAspectRatio(mode.width, mode.height);
            setStatus("正在验证 " + compactMode(signal));
            notifyReadiness();
            AtomicInteger frameCount = new AtomicInteger();
            source.startPreview(mode, BANDWIDTH_FACTORS[bandwidthIndex],
                    directView.getHolder().getSurface(), frame -> {
                        if (request == generation && frameCount.incrementAndGet() == 3) {
                            activity.runOnUiThread(() -> prepareRouter(request, mode, allowFallback));
                        }
                    }, new DirectUvcCameraSource.PreviewCallback() {
                        @Override
                        public void onConfigured() {
                        }

                        @Override
                        public void onError(Throwable error) {
                            retryMode(request, mode, allowFallback, readable(error));
                        }
                    });
            handler.postDelayed(() -> retryMode(request, mode, allowFallback, "没有收到视频帧"),
                    DIRECT_FRAME_TIMEOUT_MS);
        }

        void prepareRouter(int request, Size mode, boolean allowFallback) {
            if (request != generation || source == null) return;
            handler.removeCallbacksAndMessages(null);
            source.clearFrameCallback();
            setStatus("正在准备录制分流…");
            source.prepareRecordingRouter(mode, new DirectUvcCameraSource.PreviewCallback() {
                @Override
                public void onConfigured() {
                    if (request != generation) return;
                    routerReady = true;
                    setStatus("正在确认分屏画面…");
                    attachTexture(textureView.getWidth(), textureView.getHeight());
                    handler.postDelayed(() -> retryMode(request, mode, allowFallback,
                            "分屏画面没有收到帧"), TEXTURE_FRAME_TIMEOUT_MS);
                }

                @Override
                public void onError(Throwable error) {
                    retryMode(request, mode, allowFallback, readable(error));
                }
            });
        }

        void attachTexture(int width, int height) {
            if (source != null && routerReady && textureSurface != null
                    && textureSurface.isValid() && width > 0 && height > 0) {
                source.setScreenSurface(textureSurface, width, height);
            }
        }

        void retryMode(int request, Size mode, boolean allowFallback, String reason) {
            if (request != generation || source == null) return;
            handler.removeCallbacksAndMessages(null);
            if (bandwidthIndex + 1 < BANDWIDTH_FACTORS.length) {
                bandwidthIndex++;
                setStatus("重试 USB " + Math.round(BANDWIDTH_FACTORS[bandwidthIndex] * 100) + "%");
                handler.postDelayed(() -> startMode(mode, allowFallback), 250);
                return;
            }
            if (allowFallback && fallbackIndex + 1 < fallbacks.size()) {
                fallbackIndex++;
                bandwidthIndex = 0;
                Size next = fallbacks.get(fallbackIndex);
                setStatus("自动尝试 " + sessionModeLabel(next));
                handler.postDelayed(() -> startMode(next, true), 250);
                return;
            }
            markNotReady("无稳定画面：" + reason);
        }

        void showModeDialog() {
            if (modes.isEmpty() || isRecording()) return;
            List<Size> sorted = new ArrayList<>(modes);
            sorted.sort((left, right) -> -compareSize(left, right));
            String[] labels = new String[sorted.size()];
            int checked = -1;
            for (int i = 0; i < sorted.size(); i++) {
                labels[i] = sessionModeLabel(sorted.get(i));
                if (sameMode(sorted.get(i), signal)) checked = i;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(shortLabel + " · 输入格式")
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        dialog.dismiss();
                        fallbackIndex = 0;
                        bandwidthIndex = 0;
                        List<Size> selectedFallbacks = new ArrayList<>();
                        addDistinct(selectedFallbacks, sorted.get(which));
                        fallbacks = selectedFallbacks;
                        // A manual choice must remain the manual choice. Retry USB bandwidth
                        // if needed, but never silently jump back to another resolution.
                        startMode(sorted.get(which), false);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        void markNotReady(String message) {
            ready = false;
            routerReady = false;
            lastTextureFrameAt = 0;
            handler.removeCallbacks(healthCheck);
            audioMonitorSignature = "";
            audioMonitor.stop();
            audioMeter.setUnavailable();
            audioMeter.setVisibility(View.GONE);
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            setStatus(message);
            notifyReadiness();
            notifyRecordingState();
        }

        void replaceTextureSurface(Surface replacement) {
            Surface old = textureSurface;
            textureSurface = replacement;
            if (old != null && old != replacement) old.release();
        }

        void setStatus(String message) {
            status.setText(shortLabel + "\n" + message);
        }

        void updateStatus() {
            if (signal == null) return;
            String suffix = rotation == 0 ? "" : " · 旋转 " + rotation + "°";
            setStatus(compactMode(signal) + suffix + (ready ? " · 已就绪" : ""));
        }

        String sessionModeLabel(Size size) {
            SignalInfo info = SignalInfo.from(size, formats);
            return info.width + "×" + info.height + " @ " + info.frameRateText
                    + info.scanType + "  " + info.formatName;
        }

        @Override
        public void onRecordingState(boolean mainActive, boolean auxActive,
                                     long mainDurationMs, long auxDurationMs) {
            recordingActive = mainActive;
            recordingDuration = mainDurationMs;
            notifyRecordingState();
        }

        @Override
        public void onRecordingSaved(String displayName) {
            listener.onMultiRecordingSaved(shortLabel, displayName);
        }

        @Override
        public void onRecordingWarning(RecordingEntry.Channel channel, String message) {
            listener.onMultiWarning(shortLabel, message);
        }

        @Override
        public void onRecordingError(RecordingEntry.Channel channel, String message,
                                     Throwable error) {
            listener.onMultiError(shortLabel, message, error);
        }
    }

    private static List<Size> uniqueSizes(List<Size> sizes) {
        if (sizes == null) return Collections.emptyList();
        List<Size> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Size size : sizes) {
            if (!VideoModeFilter.keep(size)) continue;
            List<Integer> rates = size.fpsList == null || size.fpsList.isEmpty()
                    ? Collections.singletonList(size.fps) : size.fpsList;
            for (int fps : rates) {
                if (fps <= 0) continue;
                String key = size.type + ":" + size.width + ":" + size.height + ":" + fps;
                if (seen.add(key)) {
                    Size expanded = size.clone();
                    expanded.fps = fps;
                    result.add(expanded);
                }
            }
        }
        return result;
    }

    private static List<Size> buildFallbacks(List<Size> sizes,
                                             AppSettings.SavedSignalMode saved) {
        List<Size> result = new ArrayList<>();
        if (saved != null) {
            for (Size size : sizes) {
                if (saved.matches(size)) {
                    addDistinct(result, size);
                    break;
                }
            }
        }
        Size best = null;
        for (Size size : sizes) if (best == null || compareSize(size, best) > 0) best = size;
        addDistinct(result, best);
        List<Size> sorted = new ArrayList<>(sizes);
        sorted.sort((left, right) -> -compareSize(left, right));
        for (Size size : sorted) addDistinct(result, size);
        return result;
    }

    private static int compareSize(Size left, Size right) {
        long leftPixels = (long) left.width * left.height;
        long rightPixels = (long) right.width * right.height;
        if (leftPixels != rightPixels) return Long.compare(leftPixels, rightPixels);
        if (left.fps != right.fps) return Integer.compare(left.fps, right.fps);
        return Boolean.compare(left.type == UVCCamera.UVC_VS_FRAME_MJPEG,
                right.type == UVCCamera.UVC_VS_FRAME_MJPEG);
    }

    private static void addDistinct(List<Size> list, Size candidate) {
        if (candidate == null) return;
        for (Size existing : list) {
            if (existing.type == candidate.type && existing.width == candidate.width
                    && existing.height == candidate.height && existing.fps == candidate.fps) return;
        }
        list.add(candidate.clone());
    }

    private static boolean sameMode(Size size, SignalInfo signal) {
        return signal != null && size.type == signal.frameType && size.width == signal.width
                && size.height == signal.height && size.fps == signal.fps;
    }

    private static String compactMode(SignalInfo signal) {
        return signal.width + "×" + signal.height + " " + signal.frameRateText
                + signal.scanType + " " + signal.formatName;
    }

    private static String readable(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
