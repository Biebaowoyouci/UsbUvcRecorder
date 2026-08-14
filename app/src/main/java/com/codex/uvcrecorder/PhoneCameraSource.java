package com.codex.uvcrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.serenegiant.opengl.renderer.RendererHolder;
import com.serenegiant.opengl.renderer.RendererHolderCallback;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Camera2 input routed through the same GPU fan-out used by UVC and RTMP inputs. */
final class PhoneCameraSource implements UvcSurfaceSource {
    interface Listener {
        void onConnecting(String detail);

        void onReady(int width, int height, int fps);

        void onError(String message, Throwable error);
    }

    private static final int SCREEN_SURFACE_ID = 0x43414D32;
    private static final long FRAME_STALL_MS = 5_000L;
    private static final long HEALTH_INTERVAL_MS = 2_000L;
    private static final long REOPEN_DELAY_MS = 650L;

    private final Context context;
    private final PhoneCameraCatalog.Device deviceInfo;
    private final PhoneCameraCatalog.Mode mode;
    private final Listener listener;
    private final CameraManager cameraManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread cameraThread = new HandlerThread("phone-camera2-input");
    private final Handler cameraHandler;
    private final Map<Integer, int[]> recordingSurfaces = new HashMap<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final int baseRotation;
    private volatile PhoneCameraControls.State controls;

    private volatile RendererHolder router;
    private volatile ImageReader imageReader;
    private volatile Yuv420SurfaceRenderer yuvRenderer;
    private volatile CameraDevice cameraDevice;
    private volatile CameraCaptureSession captureSession;
    private volatile Surface screenSurface;
    private volatile boolean screenSurfaceAttached;
    private volatile Surface displaySurface;
    private volatile boolean released;
    private volatile long lastFrameAt;
    private volatile long openStartedAt;
    private int screenWidth;
    private int screenHeight;
    private int displaySurfaceId;
    private int displayWidth;
    private int displayHeight;
    private int outputRotation;
    private boolean reopenScheduled;
    private int reopenCount;

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            long reference = lastFrameAt > 0 ? lastFrameAt : openStartedAt;
            long age = SystemClock.elapsedRealtime() - reference;
            if (reference > 0 && age > FRAME_STALL_MS) {
                scheduleReopen("手机摄像头画面中断，正在恢复", null);
                return;
            }
            mainHandler.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    PhoneCameraSource(Context context, PhoneCameraCatalog.Device device,
                      PhoneCameraCatalog.Mode mode, Listener listener) {
        this.context = context.getApplicationContext();
        this.deviceInfo = device;
        this.mode = mode;
        this.listener = listener;
        cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        baseRotation = normalizedFrameRotation(deviceInfo.lensFacing);
        controls = PhoneCameraControls.load(this.context, device);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    void start() {
        if (released || router != null) return;
        listener.onConnecting("正在打开 " + deviceInfo.label);
        try {
            router = new RendererHolder(mode.width, mode.height,
                    new RendererHolderCallback() {
                        @Override
                        public void onPrimarySurfaceCreate(Surface surface) {
                            mainHandler.post(PhoneCameraSource.this::attachPendingScreenSurface);
                        }

                        @Override
                        public void onFrameAvailable() {
                            long now = SystemClock.elapsedRealtime();
                            boolean first = lastFrameAt <= 0;
                            lastFrameAt = now;
                            if (first) mainHandler.post(PhoneCameraSource.this::notifyReady);
                        }

                        @Override
                        public void onPrimarySurfaceDestroy() {
                        }
                    });
            router.setBeautyLevel(controls.beauty / 100f);
            imageReader = ImageReader.newInstance(mode.width, mode.height,
                    ImageFormat.YUV_420_888, 5);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            openCamera();
            mainHandler.postDelayed(healthCheck, HEALTH_INTERVAL_MS);
        } catch (Throwable error) {
            listener.onError(readable(error), error);
            scheduleReopen("手机摄像头初始化失败", error);
        }
    }

    String cameraId() {
        return deviceInfo.id;
    }

    PhoneCameraCatalog.Mode selectedMode() {
        return mode;
    }

    PhoneCameraControls.State controls() {
        return controls.copy();
    }

    void updateControls(PhoneCameraControls.State next) {
        PhoneCameraControls.State safe = PhoneCameraControls.clamp(deviceInfo, next);
        controls = safe;
        PhoneCameraControls.save(context, deviceInfo, safe);
        RendererHolder holder = router;
        if (holder != null) holder.setBeautyLevel(safe.beauty / 100f);
        cameraHandler.post(() -> {
            try {
                submitRepeatingRequest();
            } catch (Throwable error) {
                mainHandler.post(() -> listener.onError(
                        "相机控制无法应用：" + readable(error), error));
            }
        });
        applyAllTransforms();
    }

    void triggerAutoFocus() {
        cameraHandler.post(() -> {
            CameraDevice camera = cameraDevice;
            CameraCaptureSession session = captureSession;
            ImageReader reader = imageReader;
            if (camera == null || session == null || reader == null) return;
            try {
                CaptureRequest.Builder request =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                request.addTarget(reader.getSurface());
                applyCaptureControls(request, controls);
                request.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession captureSession,
                                                   @NonNull CaptureRequest captureRequest,
                                                   @NonNull TotalCaptureResult result) {
                        cameraHandler.postDelayed(() -> {
                            try {
                                submitRepeatingRequest();
                            } catch (Throwable ignored) {
                            }
                        }, 160L);
                    }
                }, cameraHandler);
            } catch (Throwable error) {
                mainHandler.post(() -> listener.onError("单次对焦失败", error));
            }
        });
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            // Never encode/render stale camera frames. If the GPU is briefly
            // busy, acquireLatestImage closes the queued older images so
            // preview, recording and RTMP latency cannot grow over time.
            image = reader.acquireLatestImage();
            if (image == null || released) return;
            Yuv420SurfaceRenderer renderer = yuvRenderer;
            if (renderer == null) return;
            boolean frontCamera =
                    deviceInfo.lensFacing == CameraCharacteristics.LENS_FACING_FRONT;
            int frameRotation = frontCamera
                    ? (baseRotation + 180) % 360 : baseRotation;
            renderer.render(image, frameRotation, frontCamera);
            long now = SystemClock.elapsedRealtime();
            boolean first = lastFrameAt <= 0;
            lastFrameAt = now;
            if (first) mainHandler.post(this::notifyReady);
        } catch (IllegalStateException error) {
            scheduleReopen("手机摄像头图像队列异常，正在恢复", error);
        } catch (Throwable error) {
            scheduleReopen("手机摄像头 YUV 渲染异常，正在恢复", error);
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (released || cameraManager == null || router == null) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError("未授予手机摄像头权限", null);
            return;
        }
        final int openGeneration = generation.incrementAndGet();
        lastFrameAt = 0;
        openStartedAt = SystemClock.elapsedRealtime();
        cameraHandler.post(() -> {
            if (released || openGeneration != generation.get()) return;
            closeCameraOnly();
            try {
                cameraManager.openCamera(deviceInfo.openId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        if (released || openGeneration != generation.get()) {
                            camera.close();
                            return;
                        }
                        cameraDevice = camera;
                        createSession(camera, openGeneration);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        camera.close();
                        if (cameraDevice == camera) cameraDevice = null;
                        scheduleReopen("手机摄像头暂时断开", null);
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        camera.close();
                        if (cameraDevice == camera) cameraDevice = null;
                        scheduleReopen("手机摄像头错误 " + error, null);
                    }
                }, cameraHandler);
            } catch (Throwable error) {
                scheduleReopen("无法打开手机摄像头", error);
            }
        });
    }

    private void createSession(CameraDevice camera, int openGeneration) {
        RendererHolder holder = router;
        ImageReader reader = imageReader;
        if (released || holder == null || reader == null) return;
        if (!holder.isRunning()) {
            cameraHandler.postDelayed(() -> {
                if (!released && openGeneration == generation.get()
                        && cameraDevice == camera) {
                    createSession(camera, openGeneration);
                }
            }, 50L);
            return;
        }
        try {
            if (yuvRenderer == null) {
                yuvRenderer = new Yuv420SurfaceRenderer(
                        holder.getPrimarySurface(), mode.width, mode.height);
            }
            Surface input = reader.getSurface();
            CameraCaptureSession.StateCallback callback =
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (released || openGeneration != generation.get()
                                    || cameraDevice != camera) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                submitRepeatingRequest();
                                reopenScheduled = false;
                            } catch (Throwable error) {
                                scheduleReopen("手机摄像头无法开始预览", error);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            session.close();
                            scheduleReopen("手机摄像头不支持所选模式", null);
                        }
                    };
            if (Build.VERSION.SDK_INT >= 28
                    && deviceInfo.physicalId != null && !deviceInfo.physicalId.isEmpty()) {
                OutputConfiguration output = new OutputConfiguration(input);
                output.setPhysicalCameraId(deviceInfo.physicalId);
                List<OutputConfiguration> outputs = Collections.singletonList(output);
                camera.createCaptureSessionByOutputConfigurations(
                        outputs, callback, cameraHandler);
            } else {
                camera.createCaptureSession(Collections.singletonList(input),
                        callback, cameraHandler);
            }
        } catch (Throwable error) {
            scheduleReopen("手机摄像头会话创建失败", error);
        }
    }

    private void submitRepeatingRequest() throws Exception {
        CameraDevice camera = cameraDevice;
        CameraCaptureSession session = captureSession;
        ImageReader reader = imageReader;
        if (released || camera == null || session == null || reader == null) {
            return;
        }
        CaptureRequest.Builder request =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        request.addTarget(reader.getSurface());
        applyCaptureControls(request, controls);
        session.setRepeatingRequest(request.build(), null, cameraHandler);
    }

    private void applyCaptureControls(CaptureRequest.Builder request,
                                      PhoneCameraControls.State state) {
        PhoneCameraCatalog.Capabilities capabilities = deviceInfo.capabilities;
        PhoneCameraControls.State safe = PhoneCameraControls.clamp(deviceInfo, state);
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (mode.aeRange != null) {
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.aeRange);
        }
        request.set(CaptureRequest.CONTROL_AWB_MODE, safe.awbMode);
        request.set(CaptureRequest.CONTROL_AF_MODE, safe.afMode);
        if (safe.afMode == CaptureRequest.CONTROL_AF_MODE_OFF
                && capabilities.minimumFocusDistance > 0f) {
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, safe.focusDistance);
        }
        if (safe.autoExposure || !capabilities.supportsManualExposure()) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    safe.exposureCompensation);
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, safe.iso);
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safe.exposureTimeNs);
            long frameDuration = Math.max(safe.exposureTimeNs,
                    1_000_000_000L / Math.max(1, mode.fps));
            request.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
        }
        applyZoom(request, safe.zoomRatio, capabilities);
        if (safe.beauty > 0) {
            if (contains(capabilities.noiseReductionModes,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                request.set(CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            }
            if (contains(capabilities.edgeModes, CaptureRequest.EDGE_MODE_OFF)) {
                request.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
            }
        }
    }

    private static void applyZoom(CaptureRequest.Builder request, float ratio,
                                  PhoneCameraCatalog.Capabilities capabilities) {
        if (Build.VERSION.SDK_INT >= 30 && capabilities.zoomRatioRange != null) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio);
            return;
        }
        Rect active = capabilities.activeArray;
        if (active == null || ratio <= 1f) return;
        int cropWidth = Math.max(2, Math.round(active.width() / ratio));
        int cropHeight = Math.max(2, Math.round(active.height() / ratio));
        int left = active.centerX() - cropWidth / 2;
        int top = active.centerY() - cropHeight / 2;
        request.set(CaptureRequest.SCALER_CROP_REGION,
                new Rect(left, top, left + cropWidth, top + cropHeight));
    }

    private void scheduleReopen(String message, Throwable error) {
        mainHandler.post(() -> {
            if (released || reopenScheduled) return;
            reopenScheduled = true;
            reopenCount++;
            listener.onConnecting(message + "（第 " + reopenCount + " 次）");
            if (error != null) listener.onError(message + "：" + readable(error), error);
            mainHandler.removeCallbacks(healthCheck);
            mainHandler.postDelayed(() -> {
                if (released) return;
                reopenScheduled = false;
                openCamera();
                mainHandler.postDelayed(healthCheck, HEALTH_INTERVAL_MS);
            }, REOPEN_DELAY_MS);
        });
    }

    private void notifyReady() {
        if (released) return;
        reopenCount = 0;
        attachPendingScreenSurface();
        applyAllTransforms();
        listener.onReady(mode.width, mode.height, mode.fps);
    }

    @Override
    public PcmAudioSubscription subscribeAudio(Context ignored) throws Exception {
        return PhoneAudioHub.subscribe(context);
    }

    @Override
    public void addRecordingSurface(Surface surface) {
        addRecordingSurface(surface, getRecordingWidth(orientedWidth(), orientedHeight()),
                getRecordingHeight(orientedWidth(), orientedHeight()));
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
            screenSurface = surface;
            boolean wasAttached = screenSurfaceAttached;
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

    private RendererHolder requireRouter(Surface surface) {
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("输出 Surface 无效");
        }
        RendererHolder holder = router;
        if (released || holder == null || !holder.isRunning()) {
            throw new IllegalStateException("手机摄像头画面尚未就绪");
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
        if (targetWidth <= 0 || targetHeight <= 0) return;
        int rotation = Math.floorMod(outputRotation, 360);
        float[] scale = controls.scaleMode == PhoneCameraControls.SCALE_FIT
                ? VideoLayout.fitCenterScale(mode.width, mode.height,
                rotation, targetWidth, targetHeight)
                : VideoLayout.centerCropScale(mode.width, mode.height,
                rotation, targetWidth, targetHeight);
        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        Matrix.scaleM(matrix, 0, scale[0], scale[1], 1f);
        Matrix.rotateM(matrix, 0, rotation, 0f, 0f, -1f);
        try {
            holder.setSlaveMvpMatrix(id, matrix);
        } catch (Throwable ignored) {
        }
    }

    void release() {
        if (released) return;
        released = true;
        generation.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        cameraHandler.post(() -> {
            closeCameraOnly();
            Yuv420SurfaceRenderer renderer = yuvRenderer;
            yuvRenderer = null;
            if (renderer != null) {
                try {
                    renderer.release();
                } catch (Throwable ignored) {
                }
            }
            ImageReader reader = imageReader;
            imageReader = null;
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
            RendererHolder holder = router;
            router = null;
            if (holder != null) {
                try {
                    holder.release();
                } catch (Throwable ignored) {
                }
            }
            synchronized (recordingSurfaces) {
                recordingSurfaces.clear();
            }
            screenSurfaceAttached = false;
            cameraThread.quitSafely();
        });
    }

    private void closeCameraOnly() {
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (Throwable ignored) {
            }
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
        CameraDevice camera = cameraDevice;
        cameraDevice = null;
        if (camera != null) {
            try {
                camera.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private int orientedWidth() {
        return mode.width;
    }

    private int orientedHeight() {
        return mode.height;
    }

    /**
     * Normalizes the sensor once, independently of the Activity/display
     * orientation. Gyroscope-driven UI rotation must never alter preview,
     * recording, RTMP or HDMI pixels; only setOutputRotation may do that.
     */
    static int normalizedFrameRotation(int lensFacing) {
        return lensFacing == CameraCharacteristics.LENS_FACING_FRONT ? 180 : 0;
    }

    private static String readable(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }
}
