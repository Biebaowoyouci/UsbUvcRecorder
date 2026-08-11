package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.serenegiant.usb.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera2 input source. Sources that belong to the same logical multi-camera share one
 * CameraDevice and one capture session; each output is routed to its selected physical ID.
 */
final class PhoneCameraSource implements UvcSurfaceSource {
    interface Listener {
        void onOpened(List<Size> modes);

        void onPreviewConfigured(Size mode);

        void onClosed();

        void onError(Throwable error);
    }

    interface PreviewCallback {
        void onConfigured();

        void onError(Throwable error);
    }

    private static final Object HUB_LOCK = new Object();
    private static final Map<String, SharedCamera> HUBS = new LinkedHashMap<>();

    private final Context context;
    private final String logicalCameraId;
    private final String physicalCameraId;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Surface> recordingSurfaces = new LinkedHashSet<>();
    private final Set<Surface> displaySurfaces = new LinkedHashSet<>();
    private final SharedCamera hub;

    private volatile boolean released;
    private volatile boolean registered;
    private volatile Size selectedMode;
    private volatile Surface previewSurface;
    private volatile PreviewCallback pendingPreviewCallback;
    private int outputRotation;

    PhoneCameraSource(Context context, String logicalCameraId, Listener listener) {
        this(context, logicalCameraId, null, listener);
    }

    PhoneCameraSource(Context context, String logicalCameraId, String physicalCameraId,
                      Listener listener) {
        this.context = context.getApplicationContext();
        this.logicalCameraId = logicalCameraId;
        this.physicalCameraId = physicalCameraId;
        this.listener = listener;
        synchronized (HUB_LOCK) {
            SharedCamera existing = HUBS.get(logicalCameraId);
            if (existing == null || existing.closed) {
                existing = new SharedCamera(this.context, logicalCameraId);
                HUBS.put(logicalCameraId, existing);
            }
            hub = existing;
        }
    }

    void start() {
        if (released || registered) return;
        registered = true;
        hub.register(this);
    }

    boolean isOpened() {
        return !released && hub.isOpened();
    }

    void startPreview(Size mode, Surface surface, PreviewCallback callback) {
        if (released) {
            callback.onError(new IllegalStateException("手机摄像头已释放"));
            return;
        }
        selectedMode = mode.clone();
        previewSurface = surface;
        pendingPreviewCallback = callback;
        hub.reconfigure(this, null);
    }

    @Override
    public void addRecordingSurface(Surface surface) throws Exception {
        changeSurface(surface, true, true);
    }

    @Override
    public void removeRecordingSurface(Surface surface) {
        try {
            changeSurface(surface, false, true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void addDisplaySurface(Surface surface, int width, int height) throws Exception {
        changeSurface(surface, true, false);
    }

    @Override
    public void removeDisplaySurface(Surface surface) {
        try {
            changeSurface(surface, false, false);
        } catch (Exception ignored) {
        }
    }

    private void changeSurface(Surface surface, boolean add, boolean recording) throws Exception {
        if (surface == null || released) return;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        hub.handler.post(() -> {
            Set<Surface> targets = recording ? recordingSurfaces : displaySurfaces;
            if (add) targets.add(surface); else targets.remove(surface);
            hub.configure(this, new Completion() {
                @Override
                public void complete(Throwable error) {
                    failure.set(error);
                    latch.countDown();
                }
            });
        });
        if (!latch.await(7, TimeUnit.SECONDS)) {
            throw new IllegalStateException("手机摄像头切换录制 Surface 超时");
        }
        if (failure.get() != null) throw new Exception(failure.get());
    }

    @Override
    public PcmAudioSubscription subscribeAudio(Context context) throws Exception {
        return MicrophoneAudioHub.subscribe(context);
    }

    @Override
    public void setOutputRotation(int degrees) {
        outputRotation = ((degrees % 360) + 360) % 360;
    }

    @Override
    public int getOutputRotation() {
        return outputRotation;
    }

    @Override
    public int getRecordingWidth(int inputWidth, int inputHeight) {
        return inputWidth;
    }

    @Override
    public int getRecordingHeight(int inputWidth, int inputHeight) {
        return inputHeight;
    }

    void release() {
        if (released) return;
        released = true;
        pendingPreviewCallback = null;
        hub.unregister(this);
    }

    private void postModes(List<Size> modes) {
        mainHandler.post(() -> {
            if (!released) listener.onOpened(modes);
        });
    }

    private void postConfigured() {
        PreviewCallback callback = pendingPreviewCallback;
        pendingPreviewCallback = null;
        Size mode = selectedMode == null ? null : selectedMode.clone();
        mainHandler.post(() -> {
            if (released) return;
            if (callback != null) callback.onConfigured();
            if (mode != null) listener.onPreviewConfigured(mode);
        });
    }

    private void postError(Throwable error) {
        PreviewCallback callback = pendingPreviewCallback;
        pendingPreviewCallback = null;
        mainHandler.post(() -> {
            if (released) return;
            if (callback != null) callback.onError(error);
            listener.onError(error);
        });
    }

    private void postClosed() {
        mainHandler.post(() -> {
            if (!released) listener.onClosed();
        });
    }

    private interface Completion {
        void complete(Throwable error);
    }

    private static final class SharedCamera {
        final Context context;
        final String logicalCameraId;
        final CameraManager manager;
        final HandlerThread thread;
        final Handler handler;
        final Set<PhoneCameraSource> clients = new LinkedHashSet<>();
        final List<Completion> pendingCompletions = new ArrayList<>();
        CameraCharacteristics characteristics;
        CameraDevice device;
        CameraCaptureSession session;
        int generation;
        boolean opening;
        volatile boolean closed;

        SharedCamera(Context context, String logicalCameraId) {
            this.context = context;
            this.logicalCameraId = logicalCameraId;
            manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            thread = new HandlerThread("phone-logical-camera-" + logicalCameraId);
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        boolean isOpened() {
            return device != null && !closed;
        }

        void register(PhoneCameraSource source) {
            handler.post(() -> {
                if (closed || source.released) return;
                clients.add(source);
                try {
                    List<Size> modes = PhoneCameraCatalog.modes(context,
                            source.logicalCameraId, source.physicalCameraId);
                    source.postModes(modes);
                } catch (Throwable error) {
                    source.postError(error);
                }
            });
        }

        void unregister(PhoneCameraSource source) {
            handler.post(() -> {
                clients.remove(source);
                source.recordingSurfaces.clear();
                source.displaySurfaces.clear();
                source.previewSurface = null;
                if (clients.isEmpty()) {
                    closeLocked();
                    synchronized (HUB_LOCK) {
                        if (HUBS.get(logicalCameraId) == this) HUBS.remove(logicalCameraId);
                    }
                } else {
                    configure(null, null);
                }
            });
        }

        void reconfigure(PhoneCameraSource requester, Completion completion) {
            handler.post(() -> configure(requester, completion));
        }

        void configure(PhoneCameraSource requester, Completion completion) {
            if (completion != null) pendingCompletions.add(completion);
            if (closed) {
                failAll(new IllegalStateException("逻辑摄像头会话已关闭"));
                return;
            }
            if (!hasValidPreview()) {
                completeAll(null);
                return;
            }
            if (device == null) {
                openLocked();
                return;
            }
            createSessionLocked();
        }

        private boolean hasValidPreview() {
            for (PhoneCameraSource source : clients) {
                if (!source.released && source.previewSurface != null
                        && source.previewSurface.isValid()) return true;
            }
            return false;
        }

        private void openLocked() {
            if (opening || device != null) return;
            if (manager == null) {
                failAll(new IllegalStateException("CameraManager 不可用"));
                return;
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                failAll(new SecurityException("未授予手机摄像头权限"));
                return;
            }
            opening = true;
            try {
                characteristics = manager.getCameraCharacteristics(logicalCameraId);
                manager.openCamera(logicalCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        opening = false;
                        if (closed) {
                            camera.close();
                            return;
                        }
                        device = camera;
                        createSessionLocked();
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        opening = false;
                        camera.close();
                        if (device == camera) device = null;
                        for (PhoneCameraSource source : new ArrayList<>(clients)) {
                            source.postClosed();
                        }
                        failAll(new IllegalStateException("手机逻辑摄像头已断开"));
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        opening = false;
                        camera.close();
                        if (device == camera) device = null;
                        failAll(new IllegalStateException(
                                cameraErrorMessage(error) + "（逻辑 Camera "
                                        + logicalCameraId + "）"));
                    }
                }, handler);
            } catch (Throwable error) {
                opening = false;
                failAll(error);
            }
        }

        private void createSessionLocked() {
            CameraDevice camera = device;
            if (camera == null || closed) return;
            int request = ++generation;
            if (session != null) {
                session.close();
                session = null;
            }
            List<OutputConfiguration> configurations = new ArrayList<>();
            List<Surface> targets = new ArrayList<>();
            boolean recording = false;
            try {
                for (PhoneCameraSource source : clients) {
                    if (source.released) continue;
                    addOutput(configurations, targets, source.previewSurface,
                            source.physicalCameraId);
                    for (Surface surface : source.recordingSurfaces) {
                        addOutput(configurations, targets, surface, source.physicalCameraId);
                        recording = true;
                    }
                    for (Surface surface : source.displaySurfaces) {
                        addOutput(configurations, targets, surface, source.physicalCameraId);
                    }
                }
                if (targets.isEmpty()) {
                    completeAll(null);
                    return;
                }
                boolean recordTemplate = recording;
                CameraCaptureSession.StateCallback callback =
                        new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession configured) {
                        if (closed || request != generation || device != camera) {
                            configured.close();
                            return;
                        }
                        session = configured;
                        try {
                            CaptureRequest.Builder builder = camera.createCaptureRequest(
                                    recordTemplate ? CameraDevice.TEMPLATE_RECORD
                                            : CameraDevice.TEMPLATE_PREVIEW);
                            for (Surface target : targets) builder.addTarget(target);
                            builder.set(CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_AUTO);
                            Range<Integer> fps = selectFpsRange(maxRequestedFps());
                            if (fps != null) {
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                            }
                            configured.setRepeatingRequest(builder.build(), null, handler);
                            for (PhoneCameraSource source : clients) {
                                if (source.previewSurface != null
                                        && source.previewSurface.isValid()) {
                                    source.postConfigured();
                                }
                            }
                            completeAll(null);
                        } catch (Throwable error) {
                            failAll(error);
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession failed) {
                        failAll(new IllegalStateException(
                                "手机不支持当前物理镜头并发组合或输出数量"));
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Executor executor = command -> handler.post(command);
                    SessionConfiguration configuration = new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, configurations,
                            executor, callback);
                    camera.createCaptureSession(configuration);
                } else {
                    camera.createCaptureSession(targets, callback, handler);
                }
            } catch (Throwable error) {
                failAll(error);
            }
        }

        private void addOutput(List<OutputConfiguration> configurations,
                               List<Surface> targets, Surface surface,
                               String physicalCameraId) {
            if (surface == null || !surface.isValid()) return;
            targets.add(surface);
            OutputConfiguration output = new OutputConfiguration(surface);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && physicalCameraId != null && !physicalCameraId.isEmpty()) {
                output.setPhysicalCameraId(physicalCameraId);
            }
            configurations.add(output);
        }

        private int maxRequestedFps() {
            int result = 30;
            for (PhoneCameraSource source : clients) {
                if (source.selectedMode != null) {
                    result = Math.max(result, source.selectedMode.fps);
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private Range<Integer> selectFpsRange(int requested) {
            if (characteristics == null) return null;
            Range<Integer>[] ranges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;
            Range<Integer> best = ranges[0];
            int scoreBest = Integer.MAX_VALUE;
            for (Range<Integer> range : ranges) {
                int score = Math.abs(range.getUpper() - requested) * 10
                        + Math.abs(range.getLower() - requested);
                if (range.contains(requested)) score -= 1000;
                if (score < scoreBest) {
                    best = range;
                    scoreBest = score;
                }
            }
            return best;
        }

        private void completeAll(Throwable error) {
            for (Completion completion : new ArrayList<>(pendingCompletions)) {
                completion.complete(error);
            }
            pendingCompletions.clear();
        }

        private void failAll(Throwable error) {
            completeAll(error);
            for (PhoneCameraSource source : new ArrayList<>(clients)) {
                source.postError(error);
            }
        }

        private void closeLocked() {
            closed = true;
            generation++;
            completeAll(new IllegalStateException("手机摄像头会话已关闭"));
            if (session != null) {
                session.close();
                session = null;
            }
            if (device != null) {
                device.close();
                device = null;
            }
            thread.quitSafely();
        }

        private static String cameraErrorMessage(int error) {
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) return "摄像头已被占用";
            if (error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) {
                return "手机已达到可同时开启的摄像头数量上限";
            }
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) return "摄像头被系统禁用";
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE) return "摄像头设备错误";
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE) return "摄像头服务错误";
            return "Camera2 打开失败，错误码 " + error;
        }
    }
}
