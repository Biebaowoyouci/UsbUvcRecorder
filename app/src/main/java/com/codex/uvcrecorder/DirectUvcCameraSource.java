package com.codex.uvcrecorder;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.opengl.renderer.RendererHolder;
import com.serenegiant.opengl.renderer.RendererHolderCallback;
import com.serenegiant.usb.Format;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.USBMonitor.USBException;
import com.serenegiant.usb.UVCCamera;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens libuvc directly and sends preview frames to the SurfaceView's ANativeWindow.
 * A separate GL router is used only for encoder fan-out. This mirrors the proven preview
 * path used by classic Serenegiant-based USB camera apps and avoids a screen-preview GL hop.
 */
final class DirectUvcCameraSource implements UvcSurfaceSource {
    private static final String TAG = "DirectUvcSource";
    private static final int SCREEN_SURFACE_ID = 0x55564353;
    interface Listener {
        void onAttach(UsbDevice device);

        void onOpened(UsbDevice device, List<Size> sizes, List<Format> formats);

        void onClosed(UsbDevice device);

        void onDetach(UsbDevice device);

        void onCancel(UsbDevice device);

        void onError(UsbDevice device, Throwable error);
    }

    interface PreviewCallback {
        void onConfigured();

        void onError(Throwable error);
    }

    private final Listener listener;
    private final int targetDeviceId;
    private final int targetVendorId;
    private final int targetProductId;
    private final String targetProductName;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread cameraThread = new HandlerThread("direct-libuvc-camera");
    private final Handler cameraHandler;
    private final USBMonitor usbMonitor;
    private final AtomicInteger previewRequest = new AtomicInteger();

    private volatile boolean released;
    private volatile boolean opened;
    private volatile UsbDevice currentDevice;
    private volatile UVCCamera camera;
    private volatile RendererHolder recordingRouter;
    private volatile Surface screenSurface;
    private volatile Surface attachedScreenSurface;
    private volatile Surface displaySurface;
    private volatile int displaySurfaceId;
    private volatile int displayWidth;
    private volatile int displayHeight;
    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int inputWidth;
    private volatile int inputHeight;
    private volatile int outputRotation;
    private int openRetryCount;

    DirectUvcCameraSource(Context context, Listener listener) {
        this(context, -1, listener);
    }

    DirectUvcCameraSource(Context context, int targetDeviceId, Listener listener) {
        this.listener = listener;
        this.targetDeviceId = targetDeviceId;
        UsbDevice target = null;
        if (targetDeviceId >= 0) {
            android.hardware.usb.UsbManager manager = (android.hardware.usb.UsbManager)
                    context.getSystemService(Context.USB_SERVICE);
            if (manager != null) {
                for (UsbDevice candidate : manager.getDeviceList().values()) {
                    if (candidate.getDeviceId() == targetDeviceId) {
                        target = candidate;
                        break;
                    }
                }
            }
        }
        targetVendorId = target == null ? -1 : target.getVendorId();
        targetProductId = target == null ? -1 : target.getProductId();
        targetProductName = target == null ? null : target.getProductName();
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        usbMonitor = new USBMonitor(context.getApplicationContext(), usbListener, mainHandler);
    }

    void start() {
        if (!released) usbMonitor.register();
    }

    boolean isOpened() {
        return opened && camera != null;
    }

    UsbDevice getCurrentDevice() {
        return currentDevice;
    }

    @Override
    public PcmAudioSubscription subscribeAudio(Context context) throws Exception {
        UsbDevice device = currentDevice;
        return UsbAudioHub.subscribe(context, device == null ? null : device.getProductName());
    }

    @Override
    public void setOutputRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException("旋转角度必须是 90° 的倍数");
        }
        outputRotation = normalized;
        cameraHandler.post(() -> {
            RendererHolder router = recordingRouter;
            if (!released && router != null && router.isRunning()) {
                router.rotateTo(normalized);
                applyScreenTransform(router);
                applyDisplayTransform(router);
            }
        });
    }

    @Override
    public int getOutputRotation() {
        return outputRotation;
    }

    void setScreenSurface(Surface surface, int width, int height) {
        screenSurface = surface;
        screenWidth = width;
        screenHeight = height;
        cameraHandler.post(() -> {
            if (released) return;
            try {
                RendererHolder router = recordingRouter;
                if (router != null && router.isRunning()) {
                    attachScreenToRouter(router, surface);
                }
            } catch (Throwable error) {
                postError(currentDevice, error);
            }
        });
    }

    void startPreview(Size mode, float bandwidthFactor, Surface surface,
                      IFrameCallback frameCallback, PreviewCallback callback) {
        screenSurface = surface;
        final int request = previewRequest.incrementAndGet();
        cameraHandler.post(() -> {
            if (released || request != previewRequest.get()) return;
            UVCCamera active = camera;
            if (active == null || !opened) {
                postPreviewError(request, callback, new IllegalStateException("UVC 摄像头尚未打开"));
                return;
            }
            if (surface == null || !surface.isValid()) {
                postPreviewError(request, callback, new IllegalStateException("预览 Surface 尚未就绪"));
                return;
            }
            try {
                stopPreviewLocked(active);
                if (request != previewRequest.get()) return;

                Log.i(TAG, "startPreview device=" + currentDevice + " mode=" + mode
                        + " bandwidth=" + bandwidthFactor);
                active.setPreviewSize(mode.clone(), bandwidthFactor);
                active.setPreviewDisplay(surface);
                // RGBX is already the native preview working format. RAW requests an extra
                // RGBX->YUYV conversion for every 4K frame and can delay the first callback
                // beyond the watchdog on high-resolution DJI UVC devices.
                active.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGBX);
                active.startPreview();
                postPreviewConfigured(request, callback);
            } catch (Throwable error) {
                stopPreviewLocked(active);
                postPreviewError(request, callback, error);
            }
        });
    }

    void clearFrameCallback() {
        cameraHandler.post(() -> {
            UVCCamera active = camera;
            if (active != null && opened) {
                try {
                    active.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RGBX);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    void prepareRecordingRouter(Size mode, PreviewCallback callback) {
        final int request = previewRequest.get();
        cameraHandler.post(() -> prepareRecordingRouterLocked(request, mode, callback, 0));
    }

    private void prepareRecordingRouterLocked(int request, Size mode,
                                               PreviewCallback callback, int attempt) {
        if (released || request != previewRequest.get()) return;
        UVCCamera active = camera;
        if (active == null || !opened) {
            postPreviewError(request, callback,
                    new IllegalStateException("UVC 摄像头已断开"));
            return;
        }
        AtomicBoolean completion = new AtomicBoolean(false);
        try {
            RendererHolder old = recordingRouter;
            recordingRouter = null;
            if (old != null) old.release();
            // The SurfaceView can only have one producer. Stop the native direct
            // preview before reconnecting that Surface as a GPU renderer output.
            active.stopPreview();
            active.setPreviewDisplay((Surface) null);
            AtomicInteger routedFrames = new AtomicInteger();
            RendererHolder router = new RendererHolder(mode.width, mode.height,
                    new RendererHolderCallback() {
                        @Override
                        public void onPrimarySurfaceCreate(Surface surface) {
                        }

                        @Override
                        public void onFrameAvailable() {
                            if (routedFrames.incrementAndGet() >= 2
                                    && completion.compareAndSet(false, true)) {
                                Log.i(TAG, "GPU router received stable frames");
                                postPreviewConfigured(request, callback);
                            }
                        }

                        @Override
                        public void onPrimarySurfaceDestroy() {
                        }
                    });
            inputWidth = mode.width;
            inputHeight = mode.height;
            router.rotateTo(outputRotation);
            active.setPreviewDisplay(router.getPrimarySurface());
            recordingRouter = router;
            attachedScreenSurface = null;
            active.startPreview();
            Log.i(TAG, "recording router ready " + mode.width + "x" + mode.height
                    + " rotation=" + outputRotation + " attempt=" + (attempt + 1));
            mainHandler.postDelayed(() -> {
                if (released || request != previewRequest.get()
                        || !completion.compareAndSet(false, true)) return;
                if (attempt == 0) {
                    Log.w(TAG, "GPU router first attempt timed out; retrying once");
                    cameraHandler.postDelayed(() -> prepareRecordingRouterLocked(
                            request, mode, callback, attempt + 1), 250);
                } else {
                    postPreviewError(request, callback,
                            new IllegalStateException("GPU 分流器没有收到 UVC 视频帧"));
                }
            }, 4_000);
        } catch (Throwable error) {
            completion.set(true);
            postPreviewError(request, callback, error);
        }
    }

    @Override
    public void addRecordingSurface(Surface surface) {
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("编码器 Surface 无效");
        }
        RendererHolder router = recordingRouter;
        if (!opened || router == null || !router.isRunning()) {
            throw new IllegalStateException("UVC 录制分流尚未就绪");
        }
        router.addSlaveSurface(surface.hashCode(), surface, true);
    }

    @Override
    public void removeRecordingSurface(Surface surface) {
        RendererHolder router = recordingRouter;
        if (surface == null || router == null) return;
        try {
            router.removeSlaveSurface(surface.hashCode());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void addDisplaySurface(Surface surface, int width, int height) {
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("HDMI 输出 Surface 无效");
        }
        RendererHolder router = recordingRouter;
        if (!opened || router == null || !router.isRunning()) {
            throw new IllegalStateException("UVC 预览分流尚未就绪");
        }
        removeDisplaySurface(displaySurface);
        int id = surface.hashCode() ^ 0x48444D49;
        router.addSlaveSurface(id, surface, false);
        if (!router.isSlaveSurfaceEnable(id)) {
            throw new IllegalStateException("无法创建 HDMI 视频输出");
        }
        displaySurface = surface;
        displaySurfaceId = id;
        displayWidth = width;
        displayHeight = height;
        applyDisplayTransform(router);
    }

    @Override
    public void removeDisplaySurface(Surface surface) {
        Surface attached = displaySurface;
        if (attached == null || (surface != null && surface != attached)) return;
        RendererHolder router = recordingRouter;
        int id = displaySurfaceId;
        displaySurface = null;
        displaySurfaceId = 0;
        displayWidth = 0;
        displayHeight = 0;
        if (router != null && id != 0) {
            try {
                router.removeSlaveSurface(id);
            } catch (Throwable ignored) {
            }
        }
    }

    private void attachScreenToRouter(RendererHolder router, Surface surface) {
        if (surface != null && surface == attachedScreenSurface) {
            applyScreenTransform(router);
            return;
        }
        try {
            router.removeSlaveSurface(SCREEN_SURFACE_ID);
        } catch (Throwable ignored) {
        }
        attachedScreenSurface = null;
        if (surface == null || !surface.isValid()) return;
        router.addSlaveSurface(SCREEN_SURFACE_ID, surface, false);
        if (!router.isSlaveSurfaceEnable(SCREEN_SURFACE_ID)) return;
        attachedScreenSurface = surface;
        applyScreenTransform(router);
    }

    private void applyScreenTransform(RendererHolder router) {
        int sourceWidth = inputWidth;
        int sourceHeight = inputHeight;
        int targetWidth = screenWidth;
        int targetHeight = screenHeight;
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return;

        float[] matrix = createOutputTransform(targetWidth, targetHeight);
        if (matrix == null) return;
        try {
            router.setSlaveMvpMatrix(SCREEN_SURFACE_ID, matrix);
        } catch (IllegalStateException ignored) {
        }
    }

    private void applyDisplayTransform(RendererHolder router) {
        Surface attached = displaySurface;
        int id = displaySurfaceId;
        if (attached == null || id == 0) return;
        float[] matrix = createOutputTransform(displayWidth, displayHeight);
        if (matrix == null) return;
        try {
            router.setSlaveMvpMatrix(id, matrix);
        } catch (IllegalStateException ignored) {
        }
    }

    private float[] createOutputTransform(int targetWidth, int targetHeight) {
        int sourceWidth = inputWidth;
        int sourceHeight = inputHeight;
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return null;
        }
        boolean quarterTurn = outputRotation == 90 || outputRotation == 270;
        float rotatedWidth = quarterTurn ? sourceHeight : sourceWidth;
        float rotatedHeight = quarterTurn ? sourceWidth : sourceHeight;
        float sourceAspect = rotatedWidth / rotatedHeight;
        float targetAspect = targetWidth / (float) targetHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (targetAspect > sourceAspect) {
            scaleX = sourceAspect / targetAspect;
        } else {
            scaleY = targetAspect / sourceAspect;
        }

        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        Matrix.scaleM(matrix, 0, scaleX, scaleY, 1f);
        Matrix.rotateM(matrix, 0, outputRotation, 0f, 0f, -1f);
        return matrix;
    }

    void release() {
        if (released) return;
        released = true;
        previewRequest.incrementAndGet();
        cameraHandler.post(() -> {
            try {
                // Stop native streaming before USBMonitor closes its control blocks.
                // Reversing this order can leave the next app start with a busy/half-open
                // UVC interface on fast background/foreground transitions.
                closeCameraLocked();
            } finally {
                try {
                    usbMonitor.destroy();
                } catch (Throwable ignored) {
                }
                cameraThread.quitSafely();
            }
        });
    }

    private final USBMonitor.OnDeviceConnectListener usbListener =
            new USBMonitor.OnDeviceConnectListener() {
                @Override
                public void onAttach(UsbDevice device) {
                    if (released || !UsbDeviceCatalog.isVideoInput(device)
                            || !matchesTarget(device)) return;
                    UsbDevice selected = currentDevice;
                    if (selected != null && selected.getDeviceId() != device.getDeviceId()) return;
                    if (selected == null) {
                        currentDevice = device;
                        openRetryCount = 0;
                        listener.onAttach(device);
                    }
                    usbMonitor.requestPermission(device);
                }

                @Override
                public void onDetach(UsbDevice device) {
                    UsbDevice selected = currentDevice;
                    if (selected == null || selected.getDeviceId() != device.getDeviceId()) return;
                    previewRequest.incrementAndGet();
                    // Clear the volatile selection before the slower native close. A powered
                    // hub can re-enumerate with a new Android device id within milliseconds;
                    // keeping the old id here would make us miss that new attach callback.
                    currentDevice = null;
                    openRetryCount = 0;
                    cameraHandler.post(() -> {
                        closeCameraLocked();
                        mainHandler.post(() -> {
                            if (!released) listener.onDetach(device);
                        });
                    });
                }

                @Override
                public void onDeviceOpen(UsbDevice device, UsbControlBlock ctrlBlock,
                                         boolean createNew) {
                    if (released || currentDevice == null
                            || currentDevice.getDeviceId() != device.getDeviceId()) return;
                    cameraHandler.post(() -> openCamera(device, ctrlBlock));
                }

                @Override
                public void onDeviceClose(UsbDevice device, UsbControlBlock ctrlBlock) {
                    if (released) return;
                    mainHandler.post(() -> listener.onClosed(device));
                }

                @Override
                public void onCancel(UsbDevice device) {
                    if (currentDevice != null
                            && currentDevice.getDeviceId() == device.getDeviceId()) {
                        currentDevice = null;
                    }
                    if (!released) listener.onCancel(device);
                }

                @Override
                public void onError(UsbDevice device, USBException error) {
                    postError(device, error);
                    scheduleOpenRetry(device);
                }
            };

    private boolean matchesTarget(UsbDevice device) {
        if (targetDeviceId < 0 || targetDeviceId == device.getDeviceId()) return true;
        if (targetVendorId < 0 || targetProductId < 0
                || targetVendorId != device.getVendorId()
                || targetProductId != device.getProductId()) return false;
        if (targetProductName == null || targetProductName.trim().isEmpty()) return true;
        return targetProductName.equals(device.getProductName());
    }

    private void scheduleOpenRetry(UsbDevice device) {
        if (released || opened || device == null || openRetryCount >= 3) return;
        int retry = ++openRetryCount;
        mainHandler.postDelayed(() -> {
            UsbDevice selected = currentDevice;
            if (released || opened || selected == null
                    || selected.getDeviceId() != device.getDeviceId()) return;
            Log.w(TAG, "retrying USB open " + retry + "/3 for " + device.getDeviceName());
            usbMonitor.requestPermission(device);
        }, 900L * retry);
    }

    private void openCamera(UsbDevice device, UsbControlBlock ctrlBlock) {
        if (released || opened) return;
        UVCCamera candidate = new UVCCamera(null);
        try {
            int result = candidate.open(ctrlBlock);
            if (result != 0) throw new IllegalStateException("libuvc 打开失败，错误码 " + result);
            camera = candidate;
            opened = true;
            openRetryCount = 0;
            List<Size> sizes = candidate.getSupportedSizeList();
            List<Format> formats = candidate.getSupportedFormatList();
            Log.i(TAG, "opened " + device + " modes=" + (sizes == null ? 0 : sizes.size()));
            if (sizes != null) {
                for (Size size : sizes) {
                    if (VideoModeFilter.keep(size)) {
                        SignalInfo info = SignalInfo.from(size, formats);
                        Log.i(TAG, "production mode " + info.displayText()
                                + " nativeFps=" + size.fps + " type=" + size.type);
                    }
                }
            }
            mainHandler.post(() -> {
                if (!released && opened) listener.onOpened(device,
                        sizes == null ? Collections.emptyList() : sizes,
                        formats == null ? Collections.emptyList() : formats);
            });
        } catch (Throwable error) {
            try {
                candidate.destroy(true);
            } catch (Throwable ignored) {
            }
            postError(device, error);
            scheduleOpenRetry(device);
        }
    }

    private void stopPreviewLocked(UVCCamera active) {
        try {
            active.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RGBX);
        } catch (Throwable ignored) {
        }
        try {
            active.stopPreview();
        } catch (Throwable ignored) {
        }
        try {
            active.stopCapture();
        } catch (Throwable ignored) {
        }
        RendererHolder router = recordingRouter;
        recordingRouter = null;
        attachedScreenSurface = null;
        displaySurface = null;
        displaySurfaceId = 0;
        displayWidth = 0;
        displayHeight = 0;
        if (router != null) {
            try {
                router.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void closeCameraLocked() {
        UVCCamera active = camera;
        camera = null;
        boolean wasOpened = opened;
        opened = false;
        if (active != null) {
            stopPreviewLocked(active);
            try {
                active.destroy(true);
            } catch (Throwable ignored) {
            }
        }
        if (wasOpened && currentDevice != null) {
            UsbDevice device = currentDevice;
            mainHandler.post(() -> {
                if (!released) listener.onClosed(device);
            });
        }
    }

    private void postPreviewConfigured(int request, PreviewCallback callback) {
        mainHandler.post(() -> {
            if (!released && request == previewRequest.get()) callback.onConfigured();
        });
    }

    private void postPreviewError(int request, PreviewCallback callback, Throwable error) {
        mainHandler.post(() -> {
            if (!released && request == previewRequest.get()) callback.onError(error);
        });
    }

    private void postError(UsbDevice device, Throwable error) {
        mainHandler.post(() -> {
            if (!released) listener.onError(device, error);
        });
    }

}
