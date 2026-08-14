package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.serenegiant.usb.Format;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends AppCompatActivity implements RecordingController.Listener {
    private static final int MODE_TIMEOUT_MS = 5_000;
    private static final int GPU_FIRST_FRAME_TIMEOUT_MS = 2_500;
    private static final int PREVIEW_STALL_TIMEOUT_MS = 4_000;
    private static final int CAMERA_RELEASE_DELAY_MS = 2_000;
    private static final int FOREGROUND_RELEASE_RECHECK_MS = 1_500;
    private static final float[] BANDWIDTH_FACTORS = {1.00f, 0.50f, 0.75f, 0.35f};

    private DirectUvcCameraSource cameraSource;
    private PhoneCameraSource phoneCameraSource;
    private NetworkStreamSource networkSource;
    private RecordingController recordingController;
    private AspectRatioSurfaceView cameraView;
    private TextureView gpuCameraView;
    private Surface gpuScreenSurface;
    private View overlay;
    private TextView noSignal;
    private TextView hint;
    private TextView deviceInfo;
    private TextView signalInfo;
    private TextView recordInfo;
    private TextView auxInfo;
    private TextView rtmpStatus;
    private Button mainRecordButton;
    private Button auxRecordButton;
    private Button signalModeButton;
    private Button deviceButton;
    private Button multiDeviceButton;
    private Button outputButton;
    private View bottomControlsScroll;
    private PhoneCameraSideControls phoneCameraSideControls;
    private AudioLevelMeterView audioLevelMeter;
    private AudioLevelMonitor audioLevelMonitor;
    private UvcSurfaceSource monitoredAudioSource;
    private GridLayout multiDeviceGrid;
    private MultiDeviceController multiDeviceController;
    private HdmiOutputController hdmiOutputController;
    private RtmpStreamingController rtmpStreamingController;
    private RtmpStreamingController.State lastRtmpState =
            RtmpStreamingController.State.DISABLED;
    private GestureDetector gestureDetector;
    private UsbDevice currentDevice;
    private SignalInfo currentSignal;
    private List<Size> supportedSizes = Collections.emptyList();
    private List<Format> supportedFormats = Collections.emptyList();
    private List<Size> fallbackModes = Collections.emptyList();
    private final Handler modeHandler = new Handler(Looper.getMainLooper());
    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean previewReady;
    private int awaitingGpuGeneration = -1;
    private long lastGpuFrameAt;
    private int previewGeneration;
    private int fallbackIndex;
    private int bandwidthIndex;
    private int outputRotation;
    private int previewReferenceDisplayRotation = -1;
    private int previewDisplayCompensation;
    private DisplayManager displayManager;
    private boolean displayListenerRegistered;
    private Size selectedMode;
    private int preferredDeviceId = -1;
    private boolean multiDeviceMode;
    private boolean networkInputSelected;
    private boolean phoneInputMode;
    private PhoneCameraCatalog.Device currentPhoneCamera;
    private PhoneCameraCatalog.Mode currentPhoneMode;
    private boolean suppressOverlayGesture;
    private final List<Integer> selectedMultiDeviceIds = new ArrayList<>();

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                applyPreviewDisplayCompensation();
            }
        }
    };

    private final Runnable delayedCameraRelease = new Runnable() {
        @Override
        public void run() {
            if (started) return;
            if (shouldKeepBackgroundUvcStreaming()) {
                StreamingKeepAliveService.start(MainActivity.this);
                return;
            }
            if (UsbRecorderApplication.isAppInForeground()) {
                lifecycleHandler.postDelayed(this, FOREGROUND_RELEASE_RECHECK_MS);
            } else {
                releaseInputs();
            }
        }
    };

    private final Runnable previewHealthCheck = new Runnable() {
        @Override
        public void run() {
            if (!started || !previewReady || cameraSource == null) return;
            long age = SystemClock.elapsedRealtime() - lastGpuFrameAt;
            if (lastGpuFrameAt > 0 && age > PREVIEW_STALL_TIMEOUT_MS && !isBusy()) {
                Size retry = selectedMode == null ? null : selectedMode.clone();
                previewReady = false;
                mainRecordButton.setEnabled(false);
                noSignal.setText("UVC 画面中断，正在自动恢复…");
                noSignal.setVisibility(View.VISIBLE);
                if (retry != null) applyPreviewMode(retry, true);
                return;
            }
            healthHandler.postDelayed(this, 2_000);
        }
    };

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (started) {
                    if (phoneInputMode) initPhoneCamera();
                    else if (networkInputSelected) initNetworkStream();
                    else if (hasCameraPermission()) initCamera();
                }
                if (!hasCameraPermission()) {
                    Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
                } else if (AppSettings.isUsbAudioEnabled(this) && !hasAudioPermission()) {
                    Toast.makeText(this, R.string.audio_permission_needed, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> outputVideoPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onOutputVideoSelected);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enterImmersiveMode();
        bindViews();
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        phoneCameraSideControls = new PhoneCameraSideControls(findViewById(R.id.root),
                this, new PhoneCameraSideControls.Host() {
            @Override
            public void cycleCamera() {
                cyclePhoneCamera();
            }

            @Override
            public void showCameraMode() {
                showPhoneCameraModeDialog();
            }

            @Override
            public void toggleRecording() {
                mainRecordButton.performClick();
            }

            @Override
            public void toggleTransmission() {
                toggleCameraTransmission();
            }

            @Override
            public void openSettings() {
                openSettingsFromCameraMode();
            }

            @Override
            public void microphoneChanged() {
                onPhoneMicrophoneChanged();
            }

            @Override
            public void cameraPanelChanged() {
                updateAudioMeterPlacement();
            }
        });
        rtmpStreamingController = new RtmpStreamingController(this, this::onRtmpStateChanged);
        audioLevelMonitor = new AudioLevelMonitor(this, new AudioLevelMonitor.Listener() {
            @Override
            public void onLevel(float normalized, float db) {
                audioLevelMeter.setLevel(normalized, db);
            }

            @Override
            public void onUnavailable() {
                audioLevelMeter.setUnavailable();
            }
        });
        phoneInputMode = AppSettings.getInputMode(this) == AppSettings.InputMode.CAMERA;
        networkInputSelected = !phoneInputMode && AppSettings.isNetworkInputSelected(this)
                && AppSettings.isPullEnabled(this)
                && SettingsActivity.isValidPullUrl(AppSettings.getPullUrl(this));
        if (!phoneInputMode) restoreSavedMultiSelectionState();
        restoreOutputRotationForActiveInput();
        gpuCameraView.post(this::applyPreviewDisplayCompensation);
        hdmiOutputController = new HdmiOutputController(this,
                new HdmiOutputController.Listener() {
                    @Override
                    public void onOutputStateChanged(HdmiOutputController.Mode mode,
                                                     String displayName) {
                        refreshOutputButton(mode, displayName);
                    }

                    @Override
                    public void onOutputWarning(String message) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
        setupGestures();
        setupSurface();
        setupActions();
        requestNeededPermissions();
        hint.postDelayed(() -> hint.animate().alpha(0f).setDuration(500)
                .withEndAction(() -> hint.setVisibility(View.GONE)).start(), 4000);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        registerDisplayListener();
        lifecycleHandler.removeCallbacks(delayedCameraRelease);
        syncInputModeSettings();
        if (phoneInputMode) initPhoneCamera();
        else if (networkInputSelected) initNetworkStream();
        else if (hasCameraPermission()) initCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        refreshSettingsUi();
        ButtonAppearance.apply(findViewById(R.id.root), AppSettings.getButtonOpacity(this));
        syncInputModeSettings();
        syncNetworkInputSettings();
        if (multiDeviceController != null) {
            multiDeviceController.refreshAudioConfiguration();
            multiDeviceController.setMetersVisible(overlay.getVisibility() == View.VISIBLE);
        }
        refreshAudioLevelMonitor();
        syncRtmpStreaming();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        enterImmersiveMode();
        gpuCameraView.post(this::applyPreviewDisplayCompensation);
        updateAudioMeterPlacement();
        PhoneCameraSource source = phoneCameraSource;
        if (source != null) {
            if (gpuScreenSurface != null && gpuScreenSurface.isValid()) {
                source.setScreenSurface(gpuScreenSurface,
                        gpuCameraView.getWidth(), gpuCameraView.getHeight());
            }
        }
    }

    @Override
    protected void onStop() {
        started = false;
        unregisterDisplayListener();
        healthHandler.removeCallbacks(previewHealthCheck);
        lifecycleHandler.removeCallbacks(delayedCameraRelease);
        if (shouldKeepBackgroundUvcStreaming()) {
            // Start while this user-visible Activity is transitioning to the
            // background, before Android applies foreground-service start
            // restrictions. The delayed release then leaves UVC/RTMP intact.
            StreamingKeepAliveService.start(this);
        }
        lifecycleHandler.postDelayed(delayedCameraRelease, CAMERA_RELEASE_DELAY_MS);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lifecycleHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacksAndMessages(null);
        StreamingKeepAliveService.stop(this);
        releaseInputs();
        if (rtmpStreamingController != null) rtmpStreamingController.release();
        if (audioLevelMonitor != null) audioLevelMonitor.release();
        if (hdmiOutputController != null) hdmiOutputController.release();
        Surface surface = gpuScreenSurface;
        gpuScreenSurface = null;
        if (surface != null) surface.release();
        super.onDestroy();
    }

    private void registerDisplayListener() {
        if (displayManager == null || displayListenerRegistered) return;
        displayManager.registerDisplayListener(displayListener, lifecycleHandler);
        displayListenerRegistered = true;
        applyPreviewDisplayCompensation();
    }

    private void unregisterDisplayListener() {
        if (displayManager == null || !displayListenerRegistered) return;
        displayManager.unregisterDisplayListener(displayListener);
        displayListenerRegistered = false;
    }

    private int currentDisplayRotation() {
        Display display = getWindowManager().getDefaultDisplay();
        return display == null ? Surface.ROTATION_0 : display.getRotation();
    }

    private void applyPreviewDisplayCompensation() {
        int current = currentDisplayRotation();
        if (previewReferenceDisplayRotation < 0) {
            previewReferenceDisplayRotation = current;
        }
        int requestedCompensation = displayRotationCompensationDegrees(
                previewReferenceDisplayRotation, current);
        previewDisplayCompensation = landscapeDisplayCompensationDegrees(
                previewReferenceDisplayRotation, current);
        if (requestedCompensation != previewDisplayCompensation) {
            // MainActivity is sensorLandscape. Android 15/16 can report the portrait
            // launcher rotation briefly before WindowManager applies that request.
            // Applying this transient quarter turn to the TextureView combines it
            // with the user's UVC rotation and shrinks the frame a second time.
            previewReferenceDisplayRotation = current;
            previewDisplayCompensation = 0;
        }
        // Android rotates the entire activity when the tablet is turned to the
        // opposite landscape edge. Counter-rotate only the preview Views so
        // buttons follow the system while preview/recording pixels stay fixed.
        gpuCameraView.setRotation(previewDisplayCompensation);
        cameraView.setRotation(previewDisplayCompensation);
        if (multiDeviceController != null) {
            multiDeviceController.setPreviewDisplayCompensation(
                    previewDisplayCompensation);
        }
    }

    static int displayRotationCompensationDegrees(int referenceRotation,
                                                  int currentRotation) {
        int referenceDegrees = surfaceRotationDegrees(referenceRotation);
        int currentDegrees = surfaceRotationDegrees(currentRotation);
        return Math.floorMod(referenceDegrees - currentDegrees, 360);
    }

    static int landscapeDisplayCompensationDegrees(int referenceRotation,
                                                    int currentRotation) {
        int compensation = displayRotationCompensationDegrees(
                referenceRotation, currentRotation);
        return Math.floorMod(compensation, 180) == 0 ? compensation : 0;
    }

    private static int surfaceRotationDegrees(int rotation) {
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            suppressOverlayGesture = multiDeviceMode && multiDeviceController != null
                    && multiDeviceController.isPointOnVideo(event.getRawX(), event.getRawY());
        }
        if (!suppressOverlayGesture) gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            suppressOverlayGesture = false;
        }
        return super.dispatchTouchEvent(event);
    }

    private void bindViews() {
        cameraView = findViewById(R.id.camera_view);
        cameraView.setAspectRatio(16, 9);
        gpuCameraView = findViewById(R.id.gpu_camera_view);
        overlay = findViewById(R.id.control_overlay);
        noSignal = findViewById(R.id.no_signal);
        hint = findViewById(R.id.double_tap_hint);
        deviceInfo = findViewById(R.id.device_info);
        signalInfo = findViewById(R.id.signal_info);
        recordInfo = findViewById(R.id.record_info);
        auxInfo = findViewById(R.id.aux_info);
        rtmpStatus = findViewById(R.id.rtmp_status);
        mainRecordButton = findViewById(R.id.main_record_button);
        auxRecordButton = findViewById(R.id.aux_record_button);
        signalModeButton = findViewById(R.id.signal_mode_button);
        deviceButton = findViewById(R.id.device_button);
        multiDeviceButton = findViewById(R.id.multi_device_button);
        outputButton = findViewById(R.id.output_button);
        bottomControlsScroll = findViewById(R.id.bottom_controls_scroll);
        audioLevelMeter = findViewById(R.id.audio_level_meter);
        multiDeviceGrid = findViewById(R.id.multi_device_grid);
        mainRecordButton.setEnabled(false);
        signalModeButton.setEnabled(false);
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                setControlsVisible(overlay.getVisibility() != View.VISIBLE);
                return true;
            }

            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }
        });
    }

    private void setControlsVisible(boolean visible) {
        overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (phoneCameraSideControls != null) {
            phoneCameraSideControls.setVisible(visible && phoneInputMode);
        }
        updateAudioMeterPlacement();
        if (multiDeviceMode && multiDeviceController != null) {
            audioLevelMeter.setVisibility(View.GONE);
            multiDeviceController.setMetersVisible(visible);
        } else if (audioLevelMeter != null) {
            boolean showMeter = visible && monitoredAudioSource != null
                    && AppSettings.isUsbAudioEnabled(this) && hasAudioPermission();
            audioLevelMeter.setVisibility(showMeter ? View.VISIBLE : View.GONE);
        }
    }

    private void updateAudioMeterPlacement() {
        if (audioLevelMeter == null) return;
        int marginDp = 18;
        if (phoneInputMode && phoneCameraSideControls != null
                && phoneCameraSideControls.isVisible()) {
            marginDp = phoneCameraSideControls.isAdjustmentVisible() ? 580 : 230;
        }
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        marginDp = Math.min(marginDp, Math.max(18, screenWidthDp - 82));
        ViewGroup.LayoutParams raw = audioLevelMeter.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
        int marginPx = Math.round(marginDp * getResources().getDisplayMetrics().density);
        if (params.getMarginEnd() == marginPx) return;
        params.setMarginEnd(marginPx);
        audioLevelMeter.setLayoutParams(params);
    }

    private void setupSurface() {
        cameraView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                attachPreviewSurface();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                detachPreviewSurface();
            }
        });
        gpuCameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture,
                                                  int width, int height) {
                setGpuScreenSurface(new Surface(texture), width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture,
                                                    int width, int height) {
                UvcSurfaceSource source = activeSingleSource();
                if (source != null && gpuScreenSurface != null
                        && gpuCameraView.getVisibility() == View.VISIBLE) {
                    source.setScreenSurface(gpuScreenSurface, width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
                setGpuScreenSurface(null, 0, 0);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
                lastGpuFrameAt = SystemClock.elapsedRealtime();
                int generation = awaitingGpuGeneration;
                if (generation >= 0 && generation == previewGeneration && !previewReady) {
                    completeGpuPreview(generation);
                }
            }
        });
    }

    private void setGpuScreenSurface(Surface surface, int width, int height) {
        UvcSurfaceSource source = activeSingleSource();
        if (source != null
                && (surface == null || gpuCameraView.getVisibility() == View.VISIBLE)) {
            source.setScreenSurface(surface, width, height);
        }
        Surface old = gpuScreenSurface;
        gpuScreenSurface = surface;
        if (old != null && old != surface) old.release();
    }

    private void setupActions() {
        mainRecordButton.setOnClickListener(v -> {
            if (multiDeviceMode) {
                if (multiDeviceController == null || !multiDeviceController.isReady()) {
                    Toast.makeText(this, "等待所有多设备画面稳定后再录制", Toast.LENGTH_SHORT).show();
                    return;
                }
                multiDeviceController.toggleRecording();
                return;
            }
            if (!previewReady || recordingController == null) {
                Toast.makeText(this, "输入信号尚未通过帧检测，不能开始录制", Toast.LENGTH_SHORT).show();
                return;
            }
            recordingController.toggleMain();
        });
        auxRecordButton.setOnClickListener(v -> {
            if (recordingController != null) recordingController.toggleAux();
        });
        signalModeButton.setOnClickListener(v -> {
            if (multiDeviceMode) {
                Toast.makeText(this, "多设备模式请直接单击对应画面切换该设备分辨率",
                        Toast.LENGTH_LONG).show();
            } else {
                showSignalModeDialog();
            }
        });
        deviceButton.setOnClickListener(v -> showDeviceSwitchDialog());
        multiDeviceButton.setOnClickListener(v -> showMultiDeviceDialog());
        findViewById(R.id.rotate_button).setOnClickListener(v -> rotateOutputClockwise());
        outputButton.setOnClickListener(v -> showHdmiOutputDialog());
        findViewById(R.id.settings_button).setOnClickListener(v -> {
            if (isBusy()) {
                Toast.makeText(this, "请先停止录制再修改设置", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasCameraPermission()) missing.add(Manifest.permission.CAMERA);
        if (AppSettings.isUsbAudioEnabled(this) && !hasAudioPermission()) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!missing.isEmpty()) permissions.launch(missing.toArray(new String[0]));
    }

    private void showDeviceSwitchDialog() {
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制再切换设备", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phoneInputMode) {
            cyclePhoneCamera();
            return;
        }
        List<UsbDevice> devices = UsbDeviceCatalog.listVideoInputs(this);
        boolean networkAvailable = AppSettings.isPullEnabled(this)
                && SettingsActivity.isValidPullUrl(AppSettings.getPullUrl(this));
        if (devices.isEmpty() && !networkAvailable) {
            Toast.makeText(this, R.string.no_uvc_device, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[devices.size() + (networkAvailable ? 1 : 0)];
        int checked = -1;
        for (int i = 0; i < devices.size(); i++) {
            UsbDevice device = devices.get(i);
            labels[i] = UsbDeviceCatalog.label(device);
            if (!multiDeviceMode && !networkInputSelected && ((currentDevice != null
                    && currentDevice.getDeviceId() == device.getDeviceId())
                    || preferredDeviceId == device.getDeviceId())) checked = i;
        }
        int networkIndex = devices.size();
        if (networkAvailable) {
            labels[networkIndex] = networkInputLabel();
            if (!multiDeviceMode && networkInputSelected) checked = networkIndex;
        }
        new AlertDialog.Builder(this)
                .setTitle("切换输入设备")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (networkAvailable && which == networkIndex) switchToNetworkStream();
                    else switchToSingleDevice(devices.get(which));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMultiDeviceDialog() {
        if (phoneInputMode) {
            Toast.makeText(this, "相机模式下请使用“设备”切换手机摄像头；"
                    + "多路 UVC 分屏请先在设置切回 UVC 模式", Toast.LENGTH_LONG).show();
            return;
        }
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制再修改多设备选择", Toast.LENGTH_SHORT).show();
            return;
        }
        List<UsbDevice> devices = UsbDeviceCatalog.listVideoInputs(this);
        if (devices.size() < 2) {
            Toast.makeText(this, "至少需要连接两个 UVC/采集卡设备", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[devices.size()];
        boolean[] checked = new boolean[devices.size()];
        List<String> savedKeys = AppSettings.getMultiDeviceKeys(this);
        for (int i = 0; i < devices.size(); i++) {
            labels[i] = UsbDeviceCatalog.label(devices.get(i));
            checked[i] = selectedMultiDeviceIds.contains(devices.get(i).getDeviceId())
                    || savedKeys.contains(UsbDeviceCatalog.stableKey(devices.get(i)));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("多选分屏录制设备（最多 4 路）")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("开始分屏", (dialog, which) -> {
                    List<UsbDevice> selected = new ArrayList<>();
                    for (int i = 0; i < devices.size(); i++) {
                        if (checked[i]) selected.add(devices.get(i));
                    }
                    if (selected.size() < 2) {
                        Toast.makeText(this, "请至少选择两个设备", Toast.LENGTH_LONG).show();
                    } else if (selected.size() > 4) {
                        Toast.makeText(this, "当前版本最多同时显示和录制 4 路",
                                Toast.LENGTH_LONG).show();
                    } else {
                        switchToMultiDevices(selected);
                    }
                })
                .setNegativeButton("取消", null);
        if (multiDeviceMode) {
            builder.setNeutralButton("退出多设备", (dialog, which) -> {
                List<UsbDevice> current = UsbDeviceCatalog.listVideoInputs(this);
                if (!current.isEmpty()) switchToSingleDevice(current.get(0));
            });
        }
        builder.show();
    }

    private void switchToSingleDevice(UsbDevice device) {
        multiDeviceMode = false;
        networkInputSelected = false;
        AppSettings.setNetworkInputSelected(this, false);
        restoreOutputRotationForActiveInput();
        selectedMultiDeviceIds.clear();
        AppSettings.clearMultiDevices(this);
        preferredDeviceId = device.getDeviceId();
        AppSettings.savePreferredDevice(this, device);
        releaseMultiDeviceController();
        releaseNetworkSource();
        releaseCamera();
        multiDeviceGrid.setVisibility(View.GONE);
        cameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(0f);
        multiDeviceButton.setText(R.string.multi_device);
        noSignal.setText("正在切换到 " + UsbDeviceCatalog.label(device));
        noSignal.setVisibility(View.VISIBLE);
        lifecycleHandler.postDelayed(() -> {
            if (started && !multiDeviceMode) initCamera();
        }, 850);
    }

    private void switchToNetworkStream() {
        String url = AppSettings.getPullUrl(this);
        if (!AppSettings.isPullEnabled(this) || !SettingsActivity.isValidPullUrl(url)) {
            Toast.makeText(this, R.string.pull_url_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        multiDeviceMode = false;
        networkInputSelected = true;
        AppSettings.setNetworkInputSelected(this, true);
        restoreOutputRotationForActiveInput();
        selectedMultiDeviceIds.clear();
        AppSettings.clearMultiDevices(this);
        preferredDeviceId = -1;
        if (hdmiOutputController != null) hdmiOutputController.stop();
        releaseMultiDeviceController();
        releaseCamera();
        releaseNetworkSource();
        multiDeviceGrid.setVisibility(View.GONE);
        cameraView.setVisibility(View.INVISIBLE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        multiDeviceButton.setText(R.string.multi_device);
        noSignal.setText("正在连接 RTMP 网络流…");
        noSignal.setVisibility(View.VISIBLE);
        lifecycleHandler.postDelayed(() -> {
            if (started && networkInputSelected && !multiDeviceMode) initNetworkStream();
        }, 250);
    }

    private void switchToMultiDevices(List<UsbDevice> devices) {
        multiDeviceMode = true;
        networkInputSelected = false;
        AppSettings.setNetworkInputSelected(this, false);
        restoreOutputRotationForActiveInput();
        selectedMultiDeviceIds.clear();
        for (UsbDevice device : devices) selectedMultiDeviceIds.add(device.getDeviceId());
        AppSettings.saveMultiDevices(this, devices);
        if (hdmiOutputController != null) hdmiOutputController.stop();
        releaseNetworkSource();
        releaseCamera();
        releaseMultiDeviceController();
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.GONE);
        noSignal.setText("正在启动 " + devices.size() + " 路 UVC 分屏…");
        noSignal.setVisibility(View.VISIBLE);
        multiDeviceGrid.setVisibility(View.VISIBLE);
        multiDeviceButton.setText(R.string.multi_device_active);
        Toast.makeText(this, "多设备高分辨率采集建议使用带独立供电的 USB 3 Hub",
                Toast.LENGTH_LONG).show();
        lifecycleHandler.postDelayed(() -> {
            if (started && multiDeviceMode) createMultiDeviceController(devices);
        }, 850);
    }

    private void restoreMultiDeviceController() {
        List<UsbDevice> available = UsbDeviceCatalog.listVideoInputs(this);
        List<UsbDevice> selected = new ArrayList<>();
        for (UsbDevice device : available) {
            if (selectedMultiDeviceIds.contains(device.getDeviceId())) selected.add(device);
        }
        if (selected.size() < 2) selected = matchSavedMultiDevices(available);
        if (selected.size() < 2) {
            multiDeviceMode = false;
            selectedMultiDeviceIds.clear();
            multiDeviceGrid.setVisibility(View.GONE);
            cameraView.setVisibility(View.VISIBLE);
            gpuCameraView.setVisibility(View.VISIBLE);
            initCamera();
            return;
        }
        selectedMultiDeviceIds.clear();
        for (UsbDevice device : selected) selectedMultiDeviceIds.add(device.getDeviceId());
        createMultiDeviceController(selected);
    }

    private void restoreSavedMultiSelectionState() {
        List<UsbDevice> selected = matchSavedMultiDevices(
                UsbDeviceCatalog.listVideoInputs(this));
        if (selected.size() < 2) return;
        multiDeviceMode = true;
        networkInputSelected = false;
        AppSettings.setNetworkInputSelected(this, false);
        selectedMultiDeviceIds.clear();
        for (UsbDevice device : selected) selectedMultiDeviceIds.add(device.getDeviceId());
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.GONE);
        multiDeviceGrid.setVisibility(View.VISIBLE);
        multiDeviceButton.setText(R.string.multi_device_active);
    }

    private List<UsbDevice> matchSavedMultiDevices(List<UsbDevice> available) {
        List<String> remaining = new ArrayList<>(AppSettings.getMultiDeviceKeys(this));
        List<UsbDevice> result = new ArrayList<>();
        for (UsbDevice device : available) {
            int match = remaining.indexOf(UsbDeviceCatalog.stableKey(device));
            if (match >= 0) {
                result.add(device);
                remaining.remove(match);
            }
        }
        return result;
    }

    private void createMultiDeviceController(List<UsbDevice> devices) {
        if (multiDeviceController != null || !multiDeviceMode) return;
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.GONE);
        multiDeviceGrid.setVisibility(View.VISIBLE);
        multiDeviceController = new MultiDeviceController(this, multiDeviceGrid, devices,
                multiDeviceListener);
        multiDeviceController.setOutputRotation(outputRotation);
        multiDeviceController.setPreviewDisplayCompensation(previewDisplayCompensation);
        multiDeviceController.setMetersVisible(overlay.getVisibility() == View.VISIBLE);
    }

    private void releaseMultiDeviceController() {
        MultiDeviceController controller = multiDeviceController;
        multiDeviceController = null;
        if (controller != null) controller.release();
    }

    private void releaseInputs() {
        StreamingKeepAliveService.stop(this);
        stopRtmpStreaming();
        releasePhoneCamera();
        releaseCamera();
        releaseNetworkSource();
        releaseMultiDeviceController();
    }

    private UvcSurfaceSource activeSingleSource() {
        if (phoneInputMode) return phoneCameraSource;
        return networkInputSelected ? networkSource : cameraSource;
    }

    private void syncRtmpStreaming() {
        if (rtmpStreamingController == null) return;
        if (!AppSettings.isRtmpEnabled(this)) {
            rtmpStreamingController.sync(null, 0, 0, 0, "");
            return;
        }
        if (multiDeviceMode) {
            if (!previewReady || multiDeviceController == null) {
                rtmpStreamingController.sync(null, 0, 0, 0, "");
                return;
            }
            List<MultiDeviceController.LiveSource> sources =
                    multiDeviceController.readyLiveSources();
            if (sources.isEmpty()) {
                rtmpStreamingController.sync(null, 0, 0, 0, "");
                return;
            }
            MultiDeviceController.LiveSource current = sources.get(0);
            rtmpStreamingController.sync(current.source, current.width, current.height,
                    current.fps, current.label);
            return;
        }
        UvcSurfaceSource source = activeSingleSource();
        if (!previewReady || source == null || currentSignal == null) {
            rtmpStreamingController.sync(null, 0, 0, 0, "");
            return;
        }
        // Keep the RTMP encoder canvas tied to the input signal. Rotation is a
        // live GPU transform inside this fixed canvas, so a 90-degree tap does
        // not require tearing down and publishing a new RTMP session.
        int width = currentSignal.width;
        int height = currentSignal.height;
        String label = phoneInputMode && currentPhoneCamera != null
                ? currentPhoneCamera.label
                : networkInputSelected ? "RTMP 网络流"
                : currentDevice == null ? "当前信号" : deviceLabel(currentDevice);
        rtmpStreamingController.sync(source, width, height, currentSignal.fps, label);
    }

    private void stopRtmpStreaming() {
        if (rtmpStreamingController != null) rtmpStreamingController.stop();
        if (!shouldKeepBackgroundUvcStreaming()) {
            StreamingKeepAliveService.stop(this);
        }
    }

    private void onRtmpStateChanged(RtmpStreamingController.State state, String detail) {
        if (shouldKeepBackgroundUvcStreaming()) {
            // Start the microphone foreground-service type while the Activity
            // is still visible. Android 14+ otherwise revokes AudioRecord/UAC
            // capture when the display turns off even though video continues.
            StreamingKeepAliveService.start(this);
        } else {
            StreamingKeepAliveService.stop(this);
        }
        if (rtmpStatus == null) return;
        if (phoneCameraSideControls != null) phoneCameraSideControls.refreshTransmit();
        boolean enabled = AppSettings.isRtmpEnabled(this);
        rtmpStatus.setVisibility(enabled || state != RtmpStreamingController.State.DISABLED
                ? View.VISIBLE : View.GONE);
        rtmpStatus.setTextColor(ContextCompat.getColor(this,
                state == RtmpStreamingController.State.ERROR
                        ? R.color.record_red : R.color.accent));
        switch (state) {
            case DISABLED:
                rtmpStatus.setVisibility(View.GONE);
                break;
            case WAITING:
                rtmpStatus.setText(R.string.rtmp_status_idle);
                break;
            case PREPARING:
            case CONNECTING:
                rtmpStatus.setText(R.string.rtmp_status_connecting);
                break;
            case LIVE:
                rtmpStatus.setText(getString(R.string.rtmp_status_live, detail));
                if (lastRtmpState != RtmpStreamingController.State.LIVE) {
                    Toast.makeText(this, "RTMP 推流已连接", Toast.LENGTH_SHORT).show();
                }
                break;
            case RETRYING:
                rtmpStatus.setText(detail == null || detail.trim().isEmpty()
                        ? getString(R.string.rtmp_status_retry) : "RTMP：" + detail);
                break;
            case ERROR:
                rtmpStatus.setText(getString(R.string.rtmp_status_error, detail));
                if (lastRtmpState != RtmpStreamingController.State.ERROR) {
                    Toast.makeText(this, "RTMP 推流失败：" + detail, Toast.LENGTH_LONG).show();
                }
                break;
        }
        lastRtmpState = state;
    }

    private boolean shouldKeepBackgroundUvcStreaming() {
        return !phoneInputMode
                && !networkInputSelected
                && AppSettings.isRtmpEnabled(this)
                && rtmpStreamingController != null
                && rtmpStreamingController.isDesired();
    }

    private final MultiDeviceController.Listener multiDeviceListener =
            new MultiDeviceController.Listener() {
        @Override
        public void onMultiReadinessChanged(int readyCount, int totalCount) {
            if (!multiDeviceMode) return;
            previewReady = totalCount > 0 && readyCount == totalCount;
            deviceInfo.setText("多设备分屏  " + readyCount + "/" + totalCount + " 路就绪");
            String audioMode = multiDeviceController == null
                    ? "" : " / " + multiDeviceController.audioModeLabel();
            signalInfo.setText(totalCount + " 路独立输入 / 独立文件录制" + audioMode);
            noSignal.setVisibility(totalCount == 0 ? View.VISIBLE : View.GONE);
            signalModeButton.setEnabled(totalCount > 0);
            refreshAudioLevelMonitor();
            refreshRecordingButtons(false, false);
            auxRecordButton.setVisibility(View.GONE);
            syncRtmpStreaming();
        }

        @Override
        public void onMultiRecordingState(boolean active, long durationMs) {
            if (!multiDeviceMode) return;
            refreshRecordingButtons(active, false);
            recordInfo.setText("多设备录制 " + duration(durationMs) + "  |  每路独立文件");
        }

        @Override
        public void onMultiRecordingSaved(String deviceLabel, String displayName) {
            Toast.makeText(MainActivity.this, deviceLabel + " 已保存：" + displayName,
                    Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onMultiAudioLevel(float normalized, float db, String sourceLabel) {
            // Multi-device level meters live inside their corresponding video tiles.
        }

        @Override
        public void onMultiAudioUnavailable() {
            // Multi-device level meters live inside their corresponding video tiles.
        }

        @Override
        public void onMultiWarning(String deviceLabel, String message) {
            Toast.makeText(MainActivity.this, deviceLabel + "：" + message,
                    Toast.LENGTH_LONG).show();
        }

        @Override
        public void onMultiError(String deviceLabel, String message, Throwable error) {
            Toast.makeText(MainActivity.this, deviceLabel + "：" + message,
                    Toast.LENGTH_LONG).show();
        }
    };

    private void showMultiHdmiOutputDialog() {
        if (multiDeviceController == null) return;
        List<MultiDeviceController.LiveSource> liveSources =
                multiDeviceController.readyLiveSources();
        List<String> choices = new ArrayList<>();
        for (MultiDeviceController.LiveSource source : liveSources) {
            choices.add("输出当前信号：" + source.label);
        }
        choices.add(getString(R.string.hdmi_output_file));
        List<UsbDevice> cardOutputs = UsbDeviceCatalog.listVideoInputs(this);
        for (UsbDevice device : cardOutputs) {
            choices.add(captureCardOutputMenuLabel(device));
        }
        if (hdmiOutputController.isActive()) choices.add(getString(R.string.hdmi_output_stop));
        int fileIndex = liveSources.size();
        int cardStartIndex = fileIndex + 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.hdmi_output_title)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which < liveSources.size()) {
                        hdmiOutputController.startLive(liveSources.get(which).source);
                    } else if (which == fileIndex) {
                        if (isBusy()) {
                            Toast.makeText(this, "请先停止录制再选择输出文件",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            outputVideoPicker.launch(new String[]{"video/*"});
                        }
                    } else if (which >= cardStartIndex
                            && which < cardStartIndex + cardOutputs.size()) {
                        showCaptureCardOutputStatus(cardOutputs.get(which - cardStartIndex));
                    } else {
                        hdmiOutputController.stop();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHdmiOutputDialog() {
        if (multiDeviceMode) {
            showMultiHdmiOutputDialog();
            return;
        }
        List<String> choices = new ArrayList<>();
        choices.add(phoneInputMode ? "输出手机摄像头信号"
                : networkInputSelected ? "输出 RTMP 拉流信号"
                : getString(R.string.hdmi_output_live));
        choices.add(getString(R.string.hdmi_output_file));
        List<UsbDevice> cardOutputs = UsbDeviceCatalog.listVideoInputs(this);
        for (UsbDevice device : cardOutputs) {
            choices.add(captureCardOutputMenuLabel(device));
        }
        if (hdmiOutputController.isActive()) choices.add(getString(R.string.hdmi_output_stop));
        new AlertDialog.Builder(this)
                .setTitle(R.string.hdmi_output_title)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        UvcSurfaceSource source = activeSingleSource();
                        if (!previewReady || source == null) {
                            Toast.makeText(this, "当前输入信号尚未就绪", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        hdmiOutputController.startLive(source);
                    } else if (which == 1) {
                        if (isBusy()) {
                            Toast.makeText(this, "请先停止录制再选择输出文件",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!hdmiOutputController.hasExternalDisplay()) {
                            Toast.makeText(this, "未检测到 HDMI/USB-C 外接显示器",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        outputVideoPicker.launch(new String[]{"video/*"});
                    } else if (which >= 2 && which < 2 + cardOutputs.size()) {
                        showCaptureCardOutputStatus(cardOutputs.get(which - 2));
                    } else {
                        hdmiOutputController.stop();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCaptureCardOutputStatus(UsbDevice device) {
        boolean writableUsbVideo = UsbDeviceCatalog.isVideoOutput(device);
        String capability = writableUsbVideo
                ? "已检测到可写的 USB Video OUT 端点。"
                : "未检测到可写的 USB Video OUT 端点。";
        new AlertDialog.Builder(this)
                .setTitle("采集卡输出能力")
                .setMessage(UsbDeviceCatalog.label(device)
                        + "\n\n" + capability + "若采集卡带 HDMI LOOP OUT，"
                        + "当前 HDMI 输入由采集卡硬件自动环出，不需要 APP 启动。"
                        + "\n\n手机当前信号或文件仍通过手机的 HDMI/USB-C 外接显示输出；"
                        + "APP 不会把仅有硬件环出的采集卡伪装成可写显示器。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private String captureCardOutputMenuLabel(UsbDevice device) {
        return (UsbDeviceCatalog.isVideoOutput(device)
                ? "设备 USB 视频 OUT：" : "设备输出 / HDMI 环出状态：")
                + UsbDeviceCatalog.label(device);
    }

    private void onOutputVideoSelected(Uri uri) {
        if (uri == null || hdmiOutputController == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        hdmiOutputController.startFile(uri);
    }

    private void refreshOutputButton(HdmiOutputController.Mode mode, String displayName) {
        if (outputButton == null) return;
        boolean active = mode != HdmiOutputController.Mode.NONE;
        outputButton.setSelected(active);
        outputButton.setText(active ? R.string.hdmi_output_active : R.string.hdmi_output);
        outputButton.setContentDescription(active && displayName != null
                ? "HDMI 输出中：" + displayName : getString(R.string.hdmi_output));
    }

    private void syncInputModeSettings() {
        boolean desiredPhone =
                AppSettings.getInputMode(this) == AppSettings.InputMode.CAMERA;
        bottomControlsScroll.setVisibility(desiredPhone ? View.GONE : View.VISIBLE);
        deviceButton.setVisibility(desiredPhone ? View.GONE : View.VISIBLE);
        signalModeButton.setVisibility(desiredPhone ? View.GONE : View.VISIBLE);
        multiDeviceButton.setVisibility(desiredPhone ? View.GONE : View.VISIBLE);
        if (phoneCameraSideControls != null) {
            phoneCameraSideControls.setVisible(desiredPhone
                    && overlay.getVisibility() == View.VISIBLE);
        }
        updateAudioMeterPlacement();
        multiDeviceButton.setEnabled(!desiredPhone);
        if (desiredPhone == phoneInputMode) return;
        if (isBusy()) return;
        phoneInputMode = desiredPhone;
        multiDeviceMode = false;
        networkInputSelected = false;
        AppSettings.setNetworkInputSelected(this, false);
        restoreOutputRotationForActiveInput();
        selectedMultiDeviceIds.clear();
        releaseMultiDeviceController();
        releasePhoneCamera();
        releaseNetworkSource();
        releaseCamera();
        multiDeviceGrid.setVisibility(View.GONE);
        cameraView.setVisibility(View.INVISIBLE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        multiDeviceButton.setText(R.string.multi_device);
        if (!started || !hasCameraPermission()) return;
        if (phoneInputMode) initPhoneCamera();
        else initCamera();
    }

    private void cyclePhoneCamera() {
        if (!phoneInputMode || isBusy()) {
            if (isBusy()) {
                Toast.makeText(this, "请先停止录制再切换镜头",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        List<PhoneCameraCatalog.Device> devices = PhoneCameraCatalog.list(this);
        if (devices.isEmpty()) {
            Toast.makeText(this, "没有检测到可用的手机摄像头",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int currentIndex = -1;
        if (currentPhoneCamera != null) {
            for (int index = 0; index < devices.size(); index++) {
                if (devices.get(index).id.equals(currentPhoneCamera.id)) {
                    currentIndex = index;
                    break;
                }
            }
        }
        PhoneCameraCatalog.Device next =
                devices.get((currentIndex + 1 + devices.size()) % devices.size());
        AppSettings.setPhoneCameraId(this, next.id);
        releasePhoneCamera();
        lifecycleHandler.postDelayed(this::initPhoneCamera, 220L);
        Toast.makeText(this, "切换到 " + next.label, Toast.LENGTH_SHORT).show();
    }

    private void toggleCameraTransmission() {
        boolean enabled = AppSettings.isRtmpEnabled(this);
        if (!enabled && !SettingsActivity.isValidRtmpUrl(AppSettings.getRtmpUrl(this))) {
            Toast.makeText(this, R.string.rtmp_url_invalid, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        AppSettings.setRtmpEnabled(this, !enabled);
        if (enabled) stopRtmpStreaming();
        syncRtmpStreaming();
        if (phoneCameraSideControls != null) phoneCameraSideControls.refreshTransmit();
    }

    private void openSettingsFromCameraMode() {
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制再修改设置",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void onPhoneMicrophoneChanged() {
        stopAudioLevelMonitor();
        stopRtmpStreaming();
        refreshSettingsUi();
        refreshAudioLevelMonitor();
        lifecycleHandler.postDelayed(this::syncRtmpStreaming, 250L);
    }

    private void initPhoneCamera() {
        if (!phoneInputMode || !started || phoneCameraSource != null) return;
        restoreOutputRotationForActiveInput();
        if (!hasCameraPermission()) {
            requestNeededPermissions();
            return;
        }
        List<PhoneCameraCatalog.Device> devices = PhoneCameraCatalog.list(this);
        PhoneCameraCatalog.Device device = PhoneCameraCatalog.find(devices,
                AppSettings.getPhoneCameraId(this));
        if (device == null) {
            noSignal.setText("没有检测到可用的手机摄像头");
            noSignal.setVisibility(View.VISIBLE);
            return;
        }
        // The selected format is shared by every phone lens. A lens that
        // cannot expose the exact mode receives the closest mode without
        // replacing the user's global preference.
        int[] saved = AppSettings.getPhoneCameraMode(this);
        PhoneCameraCatalog.Mode mode = PhoneCameraCatalog.chooseMode(device, saved);
        if (mode == null) {
            noSignal.setText(device.label + " 没有可用的 Camera2 输出模式");
            noSignal.setVisibility(View.VISIBLE);
            return;
        }
        currentPhoneCamera = device;
        currentPhoneMode = mode;
        AppSettings.setPhoneCameraId(this, device.id);
        previewReady = false;
        currentSignal = null;
        cameraView.setVisibility(View.INVISIBLE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        multiDeviceGrid.setVisibility(View.GONE);
        deviceInfo.setText(device.label);
        signalInfo.setText("正在打开 " + mode.label());
        noSignal.setText("正在启动手机摄像头…");
        noSignal.setVisibility(View.VISIBLE);
        mainRecordButton.setEnabled(false);
        signalModeButton.setEnabled(false);
        PhoneCameraSource next = new PhoneCameraSource(this, device, mode,
                phoneCameraListener);
        phoneCameraSource = next;
        next.setOutputRotation(outputRotation);
        next.start();
        if (gpuScreenSurface != null && gpuScreenSurface.isValid()) {
            next.setScreenSurface(gpuScreenSurface,
                    gpuCameraView.getWidth(), gpuCameraView.getHeight());
        }
    }

    private void releasePhoneCamera() {
        stopRtmpStreaming();
        stopAudioLevelMonitor();
        previewReady = false;
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        PhoneCameraSource source = phoneCameraSource;
        phoneCameraSource = null;
        if (source != null) {
            if (hdmiOutputController != null) hdmiOutputController.detachLiveSource(source);
            source.release();
        }
        currentPhoneCamera = null;
        currentPhoneMode = null;
        if (phoneCameraSideControls != null) phoneCameraSideControls.clear();
        currentSignal = null;
        mainRecordButton.setEnabled(false);
        signalModeButton.setEnabled(false);
    }

    private final PhoneCameraSource.Listener phoneCameraListener =
            new PhoneCameraSource.Listener() {
        @Override
        public void onConnecting(String detail) {
            if (!phoneInputMode || phoneCameraSource == null) return;
            if (!isBusy()) {
                previewReady = false;
                mainRecordButton.setEnabled(false);
            }
            noSignal.setText(detail);
            noSignal.setVisibility(View.VISIBLE);
        }

        @Override
        public void onReady(int width, int height, int fps) {
            PhoneCameraSource source = phoneCameraSource;
            if (!phoneInputMode || source == null || currentPhoneCamera == null
                    || currentPhoneMode == null) return;
            SignalInfo nextSignal = SignalInfo.network(width, height, fps, "Camera2");
            boolean changed = currentSignal == null || currentSignal.width != width
                    || currentSignal.height != height || currentSignal.fps != fps;
            currentSignal = nextSignal;
            previewReady = true;
            lastGpuFrameAt = SystemClock.elapsedRealtime();
            deviceInfo.setText(currentPhoneCamera.label);
            updateSignalSummary();
            noSignal.setVisibility(View.GONE);
            signalModeButton.setEnabled(true);
            mainRecordButton.setEnabled(true);
            if (recordingController == null
                    || (changed && !recordingController.isAnythingActive())) {
                if (recordingController != null) recordingController.release();
                recordingController = new RecordingController(MainActivity.this, source,
                        currentSignal, MainActivity.this,
                        "CAM_" + currentPhoneCamera.id, true);
            }
            if (hdmiOutputController != null
                    && hdmiOutputController.getMode() == HdmiOutputController.Mode.LIVE) {
                hdmiOutputController.attachLiveSource(source);
            }
            int[] preferredMode = AppSettings.getPhoneCameraMode(MainActivity.this);
            if (!PhoneCameraCatalog.isVisibleSelection(preferredMode)) {
                AppSettings.savePhoneCameraMode(MainActivity.this, currentPhoneCamera.id,
                        currentPhoneMode.width, currentPhoneMode.height,
                        currentPhoneMode.fps);
            }
            refreshSettingsUi();
            refreshAudioLevelMonitor();
            syncRtmpStreaming();
            if (phoneCameraSideControls != null) {
                phoneCameraSideControls.bind(currentPhoneCamera, currentPhoneMode, source);
            }
            if (getIntent().getBooleanExtra("show_camera_controls", false)) {
                getIntent().removeExtra("show_camera_controls");
                lifecycleHandler.postDelayed(() -> setControlsVisible(true), 180L);
            }
        }

        @Override
        public void onError(String message, Throwable error) {
            if (!phoneInputMode || phoneCameraSource == null) return;
            noSignal.setText("手机摄像头：" + message);
            noSignal.setVisibility(View.VISIBLE);
        }
    };

    private void showPhoneCameraDialog() {
        List<PhoneCameraCatalog.Device> devices = PhoneCameraCatalog.list(this);
        if (devices.isEmpty()) {
            Toast.makeText(this, "没有检测到可用的手机摄像头",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[devices.size()];
        int checked = -1;
        for (int i = 0; i < devices.size(); i++) {
            labels[i] = devices.get(i).label + " · " + devices.get(i).modes.size() + " 种模式";
            if (currentPhoneCamera != null
                    && currentPhoneCamera.id.equals(devices.get(i).id)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择手机摄像头")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    PhoneCameraCatalog.Device selected = devices.get(which);
                    AppSettings.setPhoneCameraId(this, selected.id);
                    releasePhoneCamera();
                    lifecycleHandler.postDelayed(this::initPhoneCamera, 300);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPhoneCameraModeDialog() {
        if (currentPhoneCamera == null || currentPhoneCamera.modes.isEmpty()) return;
        List<PhoneCameraCatalog.Mode> modes = currentPhoneCamera.modes;
        String[] labels = new String[modes.size()];
        int checked = -1;
        for (int i = 0; i < modes.size(); i++) {
            labels[i] = modes.get(i).label();
            if (currentPhoneMode != null
                    && currentPhoneMode.width == modes.get(i).width
                    && currentPhoneMode.height == modes.get(i).height
                    && currentPhoneMode.fps == modes.get(i).fps) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 " + currentPhoneCamera.label + " 模式")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    PhoneCameraCatalog.Mode selected = modes.get(which);
                    AppSettings.savePhoneCameraMode(this, currentPhoneCamera.id,
                            selected.width, selected.height, selected.fps);
                    releasePhoneCamera();
                    lifecycleHandler.postDelayed(this::initPhoneCamera, 300);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void initCamera() {
        if (phoneInputMode) {
            initPhoneCamera();
            return;
        }
        if (networkInputSelected) {
            initNetworkStream();
            return;
        }
        restoreOutputRotationForActiveInput();
        if (multiDeviceMode) {
            if (multiDeviceController == null) restoreMultiDeviceController();
            return;
        }
        if (cameraSource != null) return;
        List<UsbDevice> availableDevices = UsbDeviceCatalog.listVideoInputs(this);
        boolean preferredIdPresent = false;
        for (UsbDevice device : availableDevices) {
            if (device.getDeviceId() == preferredDeviceId) preferredIdPresent = true;
        }
        if (!preferredIdPresent) preferredDeviceId = -1;
        if (preferredDeviceId < 0) {
            for (UsbDevice device : availableDevices) {
                if (AppSettings.isPreferredDevice(this, device)) {
                    preferredDeviceId = device.getDeviceId();
                    break;
                }
            }
        }
        cameraSource = preferredDeviceId >= 0
                ? new DirectUvcCameraSource(this, preferredDeviceId, cameraListener)
                : new DirectUvcCameraSource(this, cameraListener);
        cameraSource.setOutputRotation(outputRotation);
        cameraSource.start();
    }

    private void rotateOutputClockwise() {
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制再旋转，避免中途改变编码尺寸损坏文件",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (multiDeviceMode) {
            outputRotation = (outputRotation + 90) % 360;
            AppSettings.setVideoRotation(this, activeRotationProfile(), outputRotation);
            if (multiDeviceController != null) {
                multiDeviceController.setOutputRotation(outputRotation);
            }
            lifecycleHandler.postDelayed(this::syncRtmpStreaming, 350);
            return;
        }
        outputRotation = (outputRotation + 90) % 360;
        AppSettings.setVideoRotation(this, activeRotationProfile(), outputRotation);
        UvcSurfaceSource source = activeSingleSource();
        if (source != null) source.setOutputRotation(outputRotation);
        updateSignalSummary();

        if (currentSignal != null) {
            int width = outputRotation == 90 || outputRotation == 270
                    ? currentSignal.height : currentSignal.width;
            int height = outputRotation == 90 || outputRotation == 270
                    ? currentSignal.width : currentSignal.height;
            Toast.makeText(this, "预览与录制已旋转 " + outputRotation + "°，录制输出 "
                    + width + "×" + height, Toast.LENGTH_SHORT).show();
        }
        syncRtmpStreaming();
    }

    private AppSettings.VideoRotationProfile activeRotationProfile() {
        if (phoneInputMode) return AppSettings.VideoRotationProfile.CAMERA;
        if (networkInputSelected) return AppSettings.VideoRotationProfile.NETWORK;
        return AppSettings.VideoRotationProfile.UVC;
    }

    private void restoreOutputRotationForActiveInput() {
        outputRotation = AppSettings.getVideoRotation(this, activeRotationProfile());
    }

    private void releaseCamera() {
        stopRtmpStreaming();
        stopAudioLevelMonitor();
        previewGeneration++;
        awaitingGpuGeneration = -1;
        lastGpuFrameAt = 0;
        modeHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacks(previewHealthCheck);
        previewReady = false;
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        if (cameraSource != null) {
            if (hdmiOutputController != null) {
                hdmiOutputController.detachLiveSource(cameraSource);
            }
            cameraSource.clearFrameCallback();
            cameraSource.release();
            cameraSource = null;
        }
        currentDevice = null;
        currentSignal = null;
        selectedMode = null;
        supportedSizes = Collections.emptyList();
        supportedFormats = Collections.emptyList();
        noSignal.setText(R.string.waiting_usb);
        noSignal.setVisibility(View.VISIBLE);
        mainRecordButton.setEnabled(false);
        signalModeButton.setEnabled(false);
    }

    private void releaseNetworkSource() {
        stopRtmpStreaming();
        stopAudioLevelMonitor();
        previewReady = false;
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        NetworkStreamSource source = networkSource;
        networkSource = null;
        if (source != null) {
            if (hdmiOutputController != null) hdmiOutputController.detachLiveSource(source);
            source.release();
        }
        currentDevice = null;
        currentSignal = null;
        noSignal.setVisibility(View.VISIBLE);
        mainRecordButton.setEnabled(false);
        signalModeButton.setEnabled(false);
    }

    private void initNetworkStream() {
        if (!networkInputSelected || multiDeviceMode || !started) return;
        restoreOutputRotationForActiveInput();
        String url = AppSettings.getPullUrl(this);
        if (!AppSettings.isPullEnabled(this) || !SettingsActivity.isValidPullUrl(url)) {
            networkInputSelected = false;
            AppSettings.setNetworkInputSelected(this, false);
            restoreOutputRotationForActiveInput();
            noSignal.setText(R.string.pull_url_invalid);
            noSignal.setVisibility(View.VISIBLE);
            if (hasCameraPermission()) initCamera();
            return;
        }
        if (networkSource != null && networkSource.matchesUrl(url)) return;
        if (cameraSource != null) releaseCamera();
        if (networkSource != null) releaseNetworkSource();
        previewReady = false;
        currentDevice = null;
        currentSignal = null;
        cameraView.setVisibility(View.INVISIBLE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        multiDeviceGrid.setVisibility(View.GONE);
        signalModeButton.setEnabled(false);
        mainRecordButton.setEnabled(false);
        deviceInfo.setText(networkInputLabel());
        signalInfo.setText("正在协商网络视频格式…");
        noSignal.setText("正在连接 RTMP 网络流…");
        noSignal.setVisibility(View.VISIBLE);
        NetworkStreamSource next = new NetworkStreamSource(this, url, networkStreamListener);
        networkSource = next;
        next.setOutputRotation(outputRotation);
        next.start();
        if (gpuScreenSurface != null && gpuScreenSurface.isValid()) {
            next.setScreenSurface(gpuScreenSurface,
                    gpuCameraView.getWidth(), gpuCameraView.getHeight());
        }
    }

    private void syncNetworkInputSettings() {
        if (phoneInputMode) {
            networkInputSelected = false;
            return;
        }
        boolean selected = AppSettings.isNetworkInputSelected(this);
        boolean configured = AppSettings.isPullEnabled(this)
                && SettingsActivity.isValidPullUrl(AppSettings.getPullUrl(this));
        if (networkInputSelected && (!selected || !configured)) {
            networkInputSelected = false;
            restoreOutputRotationForActiveInput();
            releaseNetworkSource();
            if (started && hasCameraPermission()) initCamera();
            return;
        }
        if (selected && configured) {
            if (!networkInputSelected) {
                networkInputSelected = true;
                restoreOutputRotationForActiveInput();
            }
            if (started && (networkSource == null
                    || !networkSource.matchesUrl(AppSettings.getPullUrl(this)))) {
                releaseNetworkSource();
                initNetworkStream();
            }
        }
    }

    private final NetworkStreamSource.Listener networkStreamListener =
            new NetworkStreamSource.Listener() {
        @Override
        public void onConnecting(String detail) {
            if (!networkInputSelected || networkSource == null) return;
            noSignal.setText(detail == null || detail.trim().isEmpty()
                    ? "RTMP 网络流正在连接…" : detail);
            noSignal.setVisibility(View.VISIBLE);
        }

        @Override
        public void onReady(int width, int height, int fps, String formatName,
                            boolean audioAvailable) {
            NetworkStreamSource source = networkSource;
            if (!networkInputSelected || source == null) return;
            SignalInfo nextSignal = SignalInfo.network(width, height, fps, formatName);
            boolean changed = currentSignal == null || currentSignal.width != width
                    || currentSignal.height != height || currentSignal.fps != fps;
            currentSignal = nextSignal;
            previewReady = true;
            lastGpuFrameAt = SystemClock.elapsedRealtime();
            cameraView.setVisibility(View.INVISIBLE);
            gpuCameraView.setVisibility(View.VISIBLE);
            gpuCameraView.setAlpha(1f);
            noSignal.setVisibility(View.GONE);
            deviceInfo.setText(networkInputLabel());
            updateSignalSummary();
            signalModeButton.setEnabled(false);
            mainRecordButton.setEnabled(true);
            if (recordingController == null || (changed && !recordingController.isAnythingActive())) {
                if (recordingController != null) recordingController.release();
                recordingController = new RecordingController(MainActivity.this, source,
                        currentSignal, MainActivity.this, "RTMP", true);
            }
            if (hdmiOutputController != null
                    && hdmiOutputController.getMode() == HdmiOutputController.Mode.LIVE) {
                hdmiOutputController.attachLiveSource(source);
            }
            refreshSettingsUi();
            refreshAudioLevelMonitor();
            syncRtmpStreaming();
        }

        @Override
        public void onAudioAvailable() {
            if (!networkInputSelected || networkSource == null) return;
            stopAudioLevelMonitor();
            refreshAudioLevelMonitor();
        }

        @Override
        public void onError(String message, Throwable error) {
            if (!networkInputSelected || networkSource == null) return;
            previewReady = false;
            mainRecordButton.setEnabled(false);
            noSignal.setText("RTMP 拉流失败：" + message);
            noSignal.setVisibility(View.VISIBLE);
            Toast.makeText(MainActivity.this, "RTMP 拉流失败：" + message,
                    Toast.LENGTH_LONG).show();
        }
    };

    private final DirectUvcCameraSource.Listener cameraListener =
            new DirectUvcCameraSource.Listener() {
        @Override
        public void onAttach(UsbDevice device) {
            if (cameraSource != null && currentDevice == null) {
                currentDevice = device;
                deviceInfo.setText(getString(R.string.connecting_device, deviceLabel(device)));
                noSignal.setText("正在请求 USB 权限并打开 UVC 设备…");
                noSignal.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onOpened(UsbDevice device, List<Size> sizes, List<Format> formats) {
            configureOpenedCamera(device, sizes, formats);
        }

        @Override
        public void onClosed(UsbDevice device) {
            previewReady = false;
            mainRecordButton.setEnabled(false);
            noSignal.setText("UVC 预览已关闭");
            noSignal.setVisibility(View.VISIBLE);
        }

        @Override
        public void onDetach(UsbDevice device) {
            handleDetach(device);
        }

        @Override
        public void onCancel(UsbDevice device) {
            currentDevice = null;
            deviceInfo.setText(R.string.usb_permission_cancelled);
            noSignal.setVisibility(View.VISIBLE);
        }

        @Override
        public void onError(UsbDevice device, Throwable error) {
            previewReady = false;
            mainRecordButton.setEnabled(false);
            noSignal.setText("无法打开 UVC 设备");
            noSignal.setVisibility(View.VISIBLE);
            Toast.makeText(MainActivity.this, "无法打开 UVC 设备：" + readableMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    };

    private void configureOpenedCamera(UsbDevice device, List<Size> sizes, List<Format> formats) {
        if (cameraSource == null || !cameraSource.isOpened()) return;
        supportedSizes = uniqueSizes(sizes);
        supportedFormats = formats == null ? Collections.emptyList() : formats;
        if (supportedSizes.isEmpty()) {
            noSignal.setText("设备没有返回 4K、1080 或 720 的 MJPEG/YUY2 视频格式");
            noSignal.setVisibility(View.VISIBLE);
            return;
        }
        AppSettings.SavedSignalMode saved = AppSettings.getSignalMode(this, device);
        fallbackModes = buildFallbackModes(supportedSizes, saved);
        fallbackIndex = 0;
        bandwidthIndex = saved != null && !fallbackModes.isEmpty()
                && saved.matches(fallbackModes.get(0))
                ? Math.min(Math.max(0, saved.bandwidthIndex), BANDWIDTH_FACTORS.length - 1)
                : 0;
        deviceInfo.setText(deviceLabel(device));
        signalModeButton.setEnabled(true);
        applyPreviewMode(fallbackModes.get(0), true);
    }

    private void applyPreviewMode(Size mode, boolean allowFallback) {
        if (cameraSource == null || !cameraSource.isOpened()) return;
        if (isBusy()) {
            Toast.makeText(this, "录制中不能切换输入格式", Toast.LENGTH_SHORT).show();
            return;
        }
        stopRtmpStreaming();
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        cameraView.setVisibility(View.INVISIBLE);
        if (gpuScreenSurface == null || !gpuScreenSurface.isValid()) {
            selectedMode = mode.clone();
            noSignal.setText("正在等待预览画布…");
            noSignal.setVisibility(View.VISIBLE);
            return;
        }
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        selectedMode = mode.clone();
        awaitingGpuGeneration = -1;
        lastGpuFrameAt = 0;
        previewReady = false;
        mainRecordButton.setEnabled(false);
        currentSignal = SignalInfo.from(mode, supportedFormats);
        cameraView.setAspectRatio(mode.width, mode.height);
        float bandwidth = BANDWIDTH_FACTORS[bandwidthIndex];
        signalInfo.setText("正在验证  " + currentSignal.displayText() + " / USB "
                + Math.round(bandwidth * 100) + "%");
        noSignal.setText("正在协商 UVC 信号…");
        noSignal.setVisibility(View.VISIBLE);

        int generation = ++previewGeneration;
        cameraSource.startRoutedPreview(mode, bandwidth, gpuScreenSurface,
                gpuCameraView.getWidth(), gpuCameraView.getHeight(),
                new DirectUvcCameraSource.PreviewCallback() {
            @Override
            public void onConfigured() {
                if (generation == previewGeneration) {
                    finishRoutedPreview(generation, mode, bandwidth);
                }
            }

            @Override
            public void onError(Throwable error) {
                handleModeTimeout(generation, allowFallback, readableMessage(error));
            }
        });
        modeHandler.postDelayed(() -> {
            if (generation == previewGeneration && !previewReady) {
                handleModeTimeout(generation, allowFallback, "未收到视频帧");
            }
        }, MODE_TIMEOUT_MS);
    }

    private void finishRoutedPreview(int generation, Size mode, float bandwidth) {
        if (generation != previewGeneration || cameraSource == null) return;
        modeHandler.removeCallbacksAndMessages(null);
        currentSignal = SignalInfo.from(mode, supportedFormats);
        cameraSource.setOutputRotation(outputRotation);
        updateSignalSummary();
        awaitingGpuGeneration = generation;
        lastGpuFrameAt = 0;
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        cameraView.setVisibility(View.INVISIBLE);
        noSignal.setText("正在确认稳定预览画面…");
        noSignal.setVisibility(View.VISIBLE);
        cameraSource.setScreenSurface(gpuScreenSurface,
                gpuCameraView.getWidth(), gpuCameraView.getHeight());
        scheduleGpuFirstFrameCheck(generation, 0);
    }

    private void confirmPreviewMode(int generation, Size mode, float bandwidth,
                                    boolean allowFallback) {
        if (generation != previewGeneration || cameraSource == null) return;
        modeHandler.removeCallbacksAndMessages(null);
        cameraSource.clearFrameCallback();
        noSignal.setText("画面已到达，正在准备录制通道…");
        noSignal.setVisibility(View.VISIBLE);
        cameraSource.prepareRecordingRouter(mode, new DirectUvcCameraSource.PreviewCallback() {
            @Override
            public void onConfigured() {
                finishPreviewMode(generation, mode, bandwidth);
            }

            @Override
            public void onError(Throwable error) {
                if (generation != previewGeneration) return;
                handleModeTimeout(generation, allowFallback,
                        "录制分流初始化失败：" + readableMessage(error));
            }
        });
    }

    private void finishPreviewMode(int generation, Size mode, float bandwidth) {
        if (generation != previewGeneration || cameraSource == null) return;
        currentSignal = SignalInfo.from(mode, supportedFormats);
        cameraSource.setOutputRotation(outputRotation);
        updateSignalSummary();
        awaitingGpuGeneration = generation;
        lastGpuFrameAt = 0;
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(0f);
        cameraView.setVisibility(View.VISIBLE);
        noSignal.setText("正在确认稳定预览画面…");
        noSignal.setVisibility(View.VISIBLE);
        attachGpuSurfaceForConfirmation();
        scheduleGpuFirstFrameCheck(generation, 0);
    }

    private void attachGpuSurfaceForConfirmation() {
        if (cameraSource == null || gpuScreenSurface == null || !gpuScreenSurface.isValid()) return;
        cameraSource.setScreenSurface(gpuScreenSurface,
                gpuCameraView.getWidth(), gpuCameraView.getHeight());
    }

    private void scheduleGpuFirstFrameCheck(int generation, int retry) {
        modeHandler.postDelayed(() -> {
            if (generation != previewGeneration || previewReady
                    || awaitingGpuGeneration != generation || cameraSource == null) return;
            if (retry == 0) {
                cameraSource.setScreenSurface(null, 0, 0);
                gpuCameraView.post(this::attachGpuSurfaceForConfirmation);
                scheduleGpuFirstFrameCheck(generation, 1);
            } else {
                awaitingGpuGeneration = -1;
                handleModeTimeout(generation, true, "GPU 预览没有收到首帧");
            }
        }, GPU_FIRST_FRAME_TIMEOUT_MS);
    }

    private void completeGpuPreview(int generation) {
        if (generation != previewGeneration || cameraSource == null || previewReady) return;
        awaitingGpuGeneration = -1;
        modeHandler.removeCallbacksAndMessages(null);
        showGpuPreview();
        noSignal.setVisibility(View.GONE);
        previewReady = true;
        mainRecordButton.setEnabled(true);
        recordingController = new RecordingController(this, cameraSource, currentSignal, this);
        if (hdmiOutputController != null
                && hdmiOutputController.getMode() == HdmiOutputController.Mode.LIVE) {
            hdmiOutputController.attachLiveSource(cameraSource);
        }
        refreshSettingsUi();
        refreshAudioLevelMonitor();
        syncRtmpStreaming();
        AppSettings.saveSignalMode(this, currentDevice, selectedMode, bandwidthIndex);
        healthHandler.removeCallbacks(previewHealthCheck);
        healthHandler.postDelayed(previewHealthCheck, 2_000);
    }

    private void updateSignalSummary() {
        if (currentSignal == null) return;
        String rotationText = outputRotation == 0 ? "" : " / 旋转 " + outputRotation + "°";
        if (phoneInputMode) {
            signalInfo.setText(currentSignal.displayText() + " / 手机相机" + rotationText);
            return;
        }
        if (networkInputSelected) {
            signalInfo.setText(currentSignal.displayText() + " / 网络拉流" + rotationText);
            return;
        }
        signalInfo.setText(currentSignal.displayText() + " / USB "
                + Math.round(BANDWIDTH_FACTORS[bandwidthIndex] * 100) + "%" + rotationText);
    }

    private void handleModeTimeout(int generation, boolean allowFallback, String reason) {
        if (generation != previewGeneration) return;
        modeHandler.removeCallbacksAndMessages(null);
        if (cameraSource != null) cameraSource.clearFrameCallback();
        if (bandwidthIndex + 1 < BANDWIDTH_FACTORS.length) {
            bandwidthIndex++;
            Size current = selectedMode == null ? fallbackModes.get(fallbackIndex) : selectedMode;
            Toast.makeText(this, "当前带宽无画面，尝试 USB "
                    + Math.round(BANDWIDTH_FACTORS[bandwidthIndex] * 100) + "%",
                    Toast.LENGTH_SHORT).show();
            applyPreviewMode(current, allowFallback);
            return;
        }
        if (allowFallback && fallbackIndex + 1 < fallbackModes.size()) {
            fallbackIndex++;
            bandwidthIndex = 0;
            Size next = fallbackModes.get(fallbackIndex);
            Toast.makeText(this, "当前模式无画面，自动降级到 " + modeLabel(next),
                    Toast.LENGTH_SHORT).show();
            applyPreviewMode(next, true);
            return;
        }
        previewReady = false;
        mainRecordButton.setEnabled(false);
        noSignal.setText("没有收到有效 UVC 帧：" + reason + "\n请点“信号”选择 1080p30 MJPEG");
        noSignal.setVisibility(View.VISIBLE);
    }

    private void showSignalModeDialog() {
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phoneInputMode) {
            showPhoneCameraModeDialog();
            return;
        }
        if (supportedSizes.isEmpty()) return;
        List<Size> modes = new ArrayList<>(supportedSizes);
        modes.sort((left, right) -> -compareSize(left, right));
        String[] labels = new String[modes.size()];
        int checked = -1;
        for (int i = 0; i < modes.size(); i++) {
            labels[i] = modeLabel(modes.get(i));
            if (sameMode(modes.get(i), currentSignal)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 UVC 输入格式（黑屏优先选 MJPEG）")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    fallbackIndex = 0;
                    bandwidthIndex = 0;
                    applyPreviewMode(modes.get(which), false);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void handleDetach(UsbDevice device) {
        if (currentDevice == null || currentDevice.getDeviceId() != device.getDeviceId()) return;
        stopRtmpStreaming();
        previewGeneration++;
        awaitingGpuGeneration = -1;
        lastGpuFrameAt = 0;
        modeHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacks(previewHealthCheck);
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        currentDevice = null;
        preferredDeviceId = -1;
        currentSignal = null;
        selectedMode = null;
        previewReady = false;
        deviceInfo.setText(R.string.device_disconnected);
        signalInfo.setText(R.string.signal_placeholder);
        noSignal.setText(R.string.waiting_usb);
        noSignal.setVisibility(View.VISIBLE);
        mainRecordButton.setEnabled(false);
        signalModeButton.setEnabled(false);
        refreshRecordingButtons(false, false);
    }

    private void attachPreviewSurface() {
        if (!cameraView.getHolder().getSurface().isValid()) return;
        if (cameraSource != null && cameraSource.isOpened() && !previewReady
                && selectedMode != null) {
            applyPreviewMode(selectedMode, false);
        }
    }

    private void detachPreviewSurface() {
    }

    private void showDirectPreview() {
        cameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(0f);
    }

    private void showGpuPreview() {
        gpuCameraView.setAlpha(1f);
        cameraView.setVisibility(View.INVISIBLE);
        gpuCameraView.post(() -> {
            UvcSurfaceSource source = activeSingleSource();
            if (source != null && gpuScreenSurface != null && previewReady) {
                source.setScreenSurface(gpuScreenSurface,
                        gpuCameraView.getWidth(), gpuCameraView.getHeight());
            }
        });
    }

    private List<Size> uniqueSizes(List<Size> sizes) {
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

    private List<Size> buildFallbackModes(List<Size> sizes,
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
        // On a new capture card, begin with the largest advertised resolution and the
        // highest frame rate at that resolution. MJPEG wins only when the size/rate match.
        addDistinct(result, chooseBestSize(sizes));
        List<Size> sorted = new ArrayList<>(sizes);
        sorted.sort((left, right) -> -compareSize(left, right));
        for (Size size : sorted) addDistinct(result, size);
        return result;
    }

    private Size chooseBestSize(List<Size> sizes) {
        Size best = null;
        for (Size candidate : sizes) {
            if (best == null || compareSize(candidate, best) > 0) best = candidate;
        }
        return best == null ? null : best.clone();
    }

    private int compareSize(Size left, Size right) {
        long leftPixels = (long) left.width * left.height;
        long rightPixels = (long) right.width * right.height;
        if (leftPixels != rightPixels) return Long.compare(leftPixels, rightPixels);
        if (left.fps != right.fps) return Integer.compare(left.fps, right.fps);
        boolean leftMjpeg = left.type == UVCCamera.UVC_VS_FRAME_MJPEG;
        boolean rightMjpeg = right.type == UVCCamera.UVC_VS_FRAME_MJPEG;
        return Boolean.compare(leftMjpeg, rightMjpeg);
    }

    private void addDistinct(List<Size> list, Size candidate) {
        if (candidate == null) return;
        for (Size current : list) {
            if (current.type == candidate.type && current.width == candidate.width
                    && current.height == candidate.height && current.fps == candidate.fps) return;
        }
        list.add(candidate.clone());
    }

    private String modeLabel(Size size) {
        SignalInfo signal = SignalInfo.from(size, supportedFormats);
        return signal.width + "×" + signal.height + " @ " + signal.frameRateText
                + signal.scanType + "  " + signal.formatName;
    }

    private boolean sameMode(Size size, SignalInfo signal) {
        return signal != null && size.type == signal.frameType && size.width == signal.width
                && size.height == signal.height && size.fps == signal.fps;
    }

    private void refreshSettingsUi() {
        if (multiDeviceMode) {
            auxRecordButton.setVisibility(View.GONE);
            auxInfo.setVisibility(View.GONE);
            mainRecordButton.setText(R.string.record_single);
            boolean active = multiDeviceController != null && multiDeviceController.isRecording();
            recordInfo.setText(active ? "多设备录制中  |  每路独立文件"
                    : "多设备待机  |  每路独立文件");
            refreshRecordingButtons(active, false);
            return;
        }
        boolean dual = AppSettings.isDualEnabled(this);
        auxRecordButton.setVisibility(dual ? View.VISIBLE : View.GONE);
        auxInfo.setVisibility(dual ? View.VISIBLE : View.GONE);
        mainRecordButton.setText(dual ? R.string.record_main : R.string.record_single);
        AppSettings.Container container = AppSettings.getContainer(this);
        AppSettings.VideoCodec codec = AppSettings.getVideoCodec(this);
        int rate = AppSettings.getBitrateMbps(this);
        String rateText = rate == 0 ? getString(R.string.automatic_bitrate)
                : getString(R.string.bitrate_mbps, rate);
        String audioLabel = phoneInputMode ? phoneAudioLabel()
                : networkInputSelected ? "网络音频" : "UAC";
        String formatText = container.label + " / " + codec.label
                + (AppSettings.isUsbAudioEnabled(this) ? " / " + audioLabel : " / 无音频");
        recordInfo.setText(getString(R.string.record_info_format, "00:00:00", formatText, rateText));
        refreshRecordingButtons(recordingController != null && recordingController.isMainActive(),
                recordingController != null && recordingController.isAuxActive());
    }

    private void refreshAudioLevelMonitor() {
        if (audioLevelMonitor == null || audioLevelMeter == null) return;
        if (multiDeviceMode && multiDeviceController != null) {
            if (monitoredAudioSource != null) {
                monitoredAudioSource = null;
                audioLevelMonitor.stop();
            }
            audioLevelMeter.setVisibility(View.GONE);
            multiDeviceController.setMetersVisible(overlay.getVisibility() == View.VISIBLE);
            return;
        }
        UvcSurfaceSource source = null;
        UvcSurfaceSource active = activeSingleSource();
        if (AppSettings.isUsbAudioEnabled(this) && previewReady && active != null
                && (!active.requiresRecordAudioPermission() || hasAudioPermission())) {
            source = active;
        }
        if (source == null) {
            stopAudioLevelMonitor();
            return;
        }
        audioLevelMeter.setVisibility(overlay.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
        if (source == monitoredAudioSource) return;
        monitoredAudioSource = source;
        audioLevelMonitor.start(source);
    }

    private void stopAudioLevelMonitor() {
        monitoredAudioSource = null;
        if (audioLevelMonitor != null) audioLevelMonitor.stop();
        if (audioLevelMeter != null) {
            audioLevelMeter.setUnavailable();
            audioLevelMeter.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRecordingState(boolean mainActive, boolean auxActive, long mainDurationMs,
                                 long auxDurationMs) {
        refreshRecordingButtons(mainActive, auxActive);
        AppSettings.Container container = AppSettings.getContainer(this);
        AppSettings.VideoCodec codec = AppSettings.getVideoCodec(this);
        int rate = AppSettings.getBitrateMbps(this);
        String rateText = rate == 0 ? getString(R.string.automatic_bitrate)
                : getString(R.string.bitrate_mbps, rate);
        String audioLabel = phoneInputMode ? phoneAudioLabel()
                : networkInputSelected ? "网络音频" : "UAC";
        String formatText = container.label + " / " + codec.label
                + (AppSettings.isUsbAudioEnabled(this) ? " / " + audioLabel : " / 无音频");
        recordInfo.setText(getString(R.string.record_info_format, duration(mainDurationMs),
                formatText, rateText));
        auxInfo.setText(getString(R.string.aux_info_format, duration(auxDurationMs)));
    }

    @Override
    public void onRecordingSaved(String displayName) {
        Toast.makeText(this, "已保存 " + displayName, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordingWarning(RecordingEntry.Channel channel, String message) {
        String name = channel == RecordingEntry.Channel.AUX ? "辅路" : "主路";
        Toast.makeText(this, name + "：" + message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRecordingError(RecordingEntry.Channel channel, String message, Throwable error) {
        String name = channel == RecordingEntry.Channel.AUX ? "辅路" : "主路";
        Toast.makeText(this, name + "录制失败：" + message, Toast.LENGTH_LONG).show();
    }

    private void refreshRecordingButtons(boolean mainActive, boolean auxActive) {
        mainRecordButton.setSelected(mainActive);
        auxRecordButton.setSelected(auxActive);
        mainRecordButton.setTextColor(mainActive ? ContextCompat.getColor(this, R.color.record_red) : Color.WHITE);
        auxRecordButton.setTextColor(auxActive ? ContextCompat.getColor(this, R.color.record_orange) : Color.WHITE);
        mainRecordButton.setEnabled(previewReady || mainActive);
        auxRecordButton.setEnabled(mainActive);
        mainRecordButton.setContentDescription(mainActive ? "停止主路录制" : "开始主路录制");
        auxRecordButton.setContentDescription(auxActive ? "停止辅路录制" : "开始辅路录制");
        if (phoneCameraSideControls != null) {
            phoneCameraSideControls.setRecording(mainActive);
        }
    }

    private boolean isBusy() {
        return (recordingController != null && recordingController.isAnythingActive())
                || (multiDeviceController != null && multiDeviceController.isRecording());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String deviceLabel(UsbDevice device) {
        String product = device.getProductName();
        if (product != null && !product.trim().isEmpty()) return product;
        return String.format(Locale.US, "UVC %04X:%04X", device.getVendorId(), device.getProductId());
    }

    private String networkInputLabel() {
        String url = AppSettings.getPullUrl(this);
        String protocol = NetworkStreamSource.isHttpFlvUrl(url)
                ? "HTTP-FLV 网络拉流" : "RTMP 网络拉流";
        String host = Uri.parse(url).getHost();
        return host == null || host.trim().isEmpty()
                ? protocol : protocol + " · " + host;
    }

    private String phoneAudioLabel() {
        PhoneAudioInputCatalog.Input input = PhoneAudioInputCatalog.selected(this);
        return input == null || input.automatic() ? "自动麦克风" : input.label;
    }

    private String duration(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600,
                (seconds % 3600) / 60, seconds % 60);
    }

    private static String readableMessage(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(),
                getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
