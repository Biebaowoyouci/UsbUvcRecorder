package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
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
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends AppCompatActivity implements RecordingController.Listener {
    private static final int REQUIRED_TEST_FRAMES = 3;
    private static final int MODE_TIMEOUT_MS = 5_000;
    private static final int GPU_FIRST_FRAME_TIMEOUT_MS = 2_500;
    private static final int PREVIEW_STALL_TIMEOUT_MS = 4_000;
    private static final int CAMERA_RELEASE_DELAY_MS = 2_000;
    private static final int FOREGROUND_RELEASE_RECHECK_MS = 1_500;
    private static final float[] BANDWIDTH_FACTORS = {1.00f, 0.50f, 0.75f, 0.35f};

    private DirectUvcCameraSource cameraSource;
    private PhoneCameraSource phoneCameraSource;
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
    private Button mainRecordButton;
    private Button auxRecordButton;
    private Button signalModeButton;
    private Button deviceButton;
    private Button multiDeviceButton;
    private Button outputButton;
    private AudioLevelMeterView audioLevelMeter;
    private AudioLevelMonitor audioLevelMonitor;
    private UvcSurfaceSource monitoredAudioSource;
    private GridLayout multiDeviceGrid;
    private MultiDeviceController multiDeviceController;
    private HdmiOutputController hdmiOutputController;
    private GestureDetector gestureDetector;
    private UsbDevice currentDevice;
    private VideoInputDevice currentInput;
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
    private Size selectedMode;
    private int preferredDeviceId = -1;
    private String preferredInputKey = "";
    private boolean multiDeviceMode;
    private boolean suppressOverlayGesture;
    private final List<String> selectedMultiDeviceKeys = new ArrayList<>();

    private final Runnable delayedCameraRelease = new Runnable() {
        @Override
        public void run() {
            if (started) return;
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
                if (hasCameraPermission() && started) initCamera();
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
        restoreSavedMultiSelectionState();
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
        lifecycleHandler.removeCallbacks(delayedCameraRelease);
        if (hasCameraPermission()) initCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        refreshSettingsUi();
        ButtonAppearance.apply(findViewById(R.id.root), AppSettings.getButtonOpacity(this));
        if (multiDeviceController != null) {
            multiDeviceController.refreshAudioConfiguration();
            multiDeviceController.setMetersVisible(overlay.getVisibility() == View.VISIBLE);
        }
        refreshAudioLevelMonitor();
    }

    @Override
    protected void onStop() {
        started = false;
        healthHandler.removeCallbacks(previewHealthCheck);
        lifecycleHandler.removeCallbacks(delayedCameraRelease);
        lifecycleHandler.postDelayed(delayedCameraRelease, CAMERA_RELEASE_DELAY_MS);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lifecycleHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacksAndMessages(null);
        releaseInputs();
        if (audioLevelMonitor != null) audioLevelMonitor.release();
        if (hdmiOutputController != null) hdmiOutputController.release();
        Surface surface = gpuScreenSurface;
        gpuScreenSurface = null;
        if (surface != null) surface.release();
        super.onDestroy();
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
        mainRecordButton = findViewById(R.id.main_record_button);
        auxRecordButton = findViewById(R.id.aux_record_button);
        signalModeButton = findViewById(R.id.signal_mode_button);
        deviceButton = findViewById(R.id.device_button);
        multiDeviceButton = findViewById(R.id.multi_device_button);
        outputButton = findViewById(R.id.output_button);
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
        if (multiDeviceMode && multiDeviceController != null) {
            audioLevelMeter.setVisibility(View.GONE);
            multiDeviceController.setMetersVisible(visible);
        } else if (audioLevelMeter != null) {
            boolean showMeter = visible && monitoredAudioSource != null
                    && AppSettings.isUsbAudioEnabled(this) && hasAudioPermission();
            audioLevelMeter.setVisibility(showMeter ? View.VISIBLE : View.GONE);
        }
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
                if (phoneCameraSource != null && selectedMode != null && !previewReady) {
                    texture.setDefaultBufferSize(selectedMode.width, selectedMode.height);
                    layoutPhonePreview(selectedMode);
                    applyPhonePreviewMode(selectedMode);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture,
                                                    int width, int height) {
                if (cameraSource != null && gpuScreenSurface != null
                        && gpuCameraView.getVisibility() == View.VISIBLE) {
                    cameraSource.setScreenSurface(gpuScreenSurface, width, height);
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
        if (cameraSource != null
                && (surface == null || gpuCameraView.getVisibility() == View.VISIBLE)) {
            cameraSource.setScreenSurface(surface, width, height);
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
        List<VideoInputDevice> devices = VideoInputDevice.listAll(this);
        if (devices.isEmpty()) {
            Toast.makeText(this, "没有检测到 UVC、采集卡或手机摄像头", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[devices.size()];
        int checked = -1;
        for (int i = 0; i < devices.size(); i++) {
            VideoInputDevice device = devices.get(i);
            labels[i] = device.label;
            if (!multiDeviceMode && ((currentInput != null && currentInput.equals(device))
                    || preferredInputKey.equals(device.stableKey))) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("切换摄像头 / UVC / 采集卡")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    switchToSingleInput(devices.get(which));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMultiDeviceDialog() {
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制再修改多设备选择", Toast.LENGTH_SHORT).show();
            return;
        }
        List<VideoInputDevice> devices = VideoInputDevice.listAll(this);
        if (devices.size() < 2) {
            Toast.makeText(this, "至少需要两个手机摄像头、UVC 或采集卡输入", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[devices.size()];
        boolean[] checked = new boolean[devices.size()];
        List<String> savedKeys = AppSettings.getMultiDeviceKeys(this);
        for (int i = 0; i < devices.size(); i++) {
            labels[i] = devices.get(i).label;
            checked[i] = selectedMultiDeviceKeys.contains(devices.get(i).stableKey)
                    || savedKeys.contains(devices.get(i).stableKey);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("多选分屏录制设备（最多 4 路）")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("开始分屏", (dialog, which) -> {
                    List<VideoInputDevice> selected = new ArrayList<>();
                    for (int i = 0; i < devices.size(); i++) {
                        if (checked[i]) selected.add(devices.get(i));
                    }
                    if (selected.size() < 2) {
                        Toast.makeText(this, "请至少选择两个设备", Toast.LENGTH_LONG).show();
                    } else if (selected.size() > 4) {
                        Toast.makeText(this, "当前版本最多同时显示和录制 4 路",
                                Toast.LENGTH_LONG).show();
                    } else {
                        switchToMultiInputs(selected);
                    }
                })
                .setNegativeButton("取消", null);
        if (multiDeviceMode) {
            builder.setNeutralButton("退出多设备", (dialog, which) -> {
                List<VideoInputDevice> current = VideoInputDevice.listAll(this);
                if (!current.isEmpty()) switchToSingleInput(current.get(0));
            });
        }
        builder.show();
    }

    private void switchToSingleInput(VideoInputDevice device) {
        multiDeviceMode = false;
        selectedMultiDeviceKeys.clear();
        AppSettings.clearMultiDevices(this);
        currentInput = device;
        preferredInputKey = device.stableKey;
        preferredDeviceId = device.isUsb() ? device.usbDevice.getDeviceId() : -1;
        AppSettings.savePreferredInput(this, device.stableKey);
        releaseMultiDeviceController();
        releaseCamera();
        multiDeviceGrid.setVisibility(View.GONE);
        cameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(0f);
        multiDeviceButton.setText(R.string.multi_device);
        noSignal.setText("正在切换到 " + device.label);
        noSignal.setVisibility(View.VISIBLE);
        lifecycleHandler.postDelayed(() -> {
            if (started && !multiDeviceMode) initCamera();
        }, 850);
    }

    private void switchToMultiInputs(List<VideoInputDevice> devices) {
        multiDeviceMode = true;
        selectedMultiDeviceKeys.clear();
        for (VideoInputDevice device : devices) selectedMultiDeviceKeys.add(device.stableKey);
        AppSettings.saveMultiInputs(this, devices);
        if (hdmiOutputController != null) hdmiOutputController.stop();
        releaseCamera();
        releaseMultiDeviceController();
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.GONE);
        noSignal.setText("正在启动 " + devices.size() + " 路摄像头分屏…");
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
        List<VideoInputDevice> available = VideoInputDevice.listAll(this);
        List<VideoInputDevice> selected = new ArrayList<>();
        for (VideoInputDevice device : available) {
            if (selectedMultiDeviceKeys.contains(device.stableKey)) selected.add(device);
        }
        if (selected.size() < 2) selected = matchSavedMultiDevices(available);
        if (selected.size() < 2) {
            multiDeviceMode = false;
            selectedMultiDeviceKeys.clear();
            multiDeviceGrid.setVisibility(View.GONE);
            cameraView.setVisibility(View.VISIBLE);
            gpuCameraView.setVisibility(View.VISIBLE);
            initCamera();
            return;
        }
        selectedMultiDeviceKeys.clear();
        for (VideoInputDevice device : selected) selectedMultiDeviceKeys.add(device.stableKey);
        createMultiDeviceController(selected);
    }

    private void restoreSavedMultiSelectionState() {
        List<VideoInputDevice> selected = matchSavedMultiDevices(VideoInputDevice.listAll(this));
        if (selected.size() < 2) return;
        multiDeviceMode = true;
        selectedMultiDeviceKeys.clear();
        for (VideoInputDevice device : selected) selectedMultiDeviceKeys.add(device.stableKey);
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.GONE);
        multiDeviceGrid.setVisibility(View.VISIBLE);
        multiDeviceButton.setText(R.string.multi_device_active);
    }

    private List<VideoInputDevice> matchSavedMultiDevices(List<VideoInputDevice> available) {
        List<String> remaining = new ArrayList<>(AppSettings.getMultiDeviceKeys(this));
        List<VideoInputDevice> result = new ArrayList<>();
        for (VideoInputDevice device : available) {
            int match = remaining.indexOf(device.stableKey);
            if (match >= 0) {
                result.add(device);
                remaining.remove(match);
            }
        }
        return result;
    }

    private void createMultiDeviceController(List<VideoInputDevice> devices) {
        if (multiDeviceController != null || !multiDeviceMode) return;
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.GONE);
        multiDeviceGrid.setVisibility(View.VISIBLE);
        multiDeviceController = new MultiDeviceController(this, multiDeviceGrid, devices,
                multiDeviceListener);
        multiDeviceController.setMetersVisible(overlay.getVisibility() == View.VISIBLE);
    }

    private void releaseMultiDeviceController() {
        MultiDeviceController controller = multiDeviceController;
        multiDeviceController = null;
        if (controller != null) controller.release();
    }

    private void releaseInputs() {
        releaseCamera();
        releaseMultiDeviceController();
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
        choices.add(getString(R.string.hdmi_output_live));
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
                        UvcSurfaceSource liveSource = cameraSource != null
                                ? cameraSource : phoneCameraSource;
                        if (!previewReady || liveSource == null) {
                            Toast.makeText(this, "当前摄像头信号尚未就绪", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        hdmiOutputController.startLive(liveSource);
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

    private void initCamera() {
        if (multiDeviceMode) {
            if (multiDeviceController == null) restoreMultiDeviceController();
            return;
        }
        if (cameraSource != null || phoneCameraSource != null) return;
        List<VideoInputDevice> available = VideoInputDevice.listAll(this);
        if (available.isEmpty()) {
            noSignal.setText("请连接 UVC/采集卡，或确认手机摄像头权限");
            noSignal.setVisibility(View.VISIBLE);
            return;
        }
        if (preferredInputKey.isEmpty()) preferredInputKey = AppSettings.getPreferredInputKey(this);
        VideoInputDevice selected = currentInput;
        if (selected == null || !available.contains(selected)) {
            selected = null;
            for (VideoInputDevice candidate : available) {
                if (candidate.stableKey.equals(preferredInputKey)) {
                    selected = candidate;
                    break;
                }
                if (candidate.isUsb() && AppSettings.isPreferredDevice(this, candidate.usbDevice)) {
                    selected = candidate;
                }
            }
        }
        if (selected == null) selected = available.get(0);
        currentInput = selected;
        preferredInputKey = selected.stableKey;
        if (selected.isPhoneCamera()) {
            initPhoneCamera(selected);
            return;
        }
        resetPhonePreviewTransform();
        preferredDeviceId = selected.usbDevice.getDeviceId();
        currentDevice = selected.usbDevice;
        cameraSource = new DirectUvcCameraSource(this, preferredDeviceId, cameraListener);
        cameraSource.setOutputRotation(outputRotation);
        cameraSource.start();
    }

    private void initPhoneCamera(VideoInputDevice input) {
        preferredDeviceId = -1;
        currentDevice = null;
        deviceInfo.setText(input.label);
        noSignal.setText("正在打开 " + input.label + "…");
        noSignal.setVisibility(View.VISIBLE);
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        phoneCameraSource = new PhoneCameraSource(this, input.logicalCameraId,
                input.physicalCameraId, phoneCameraListener);
        phoneCameraSource.start();
    }

    private void rotateOutputClockwise() {
        if (isBusy()) {
            Toast.makeText(this, "请先停止录制再旋转，避免中途改变编码尺寸损坏文件",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (multiDeviceMode) {
            if (multiDeviceController != null) multiDeviceController.rotateClockwise();
            return;
        }
        if (phoneCameraSource != null) {
            Toast.makeText(this, "手机摄像头按传感器方向输出，当前版本不额外旋转该路",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        outputRotation = (outputRotation + 90) % 360;
        if (cameraSource != null) cameraSource.setOutputRotation(outputRotation);
        updateSignalSummary();

        if (currentSignal != null) {
            int width = outputRotation == 90 || outputRotation == 270
                    ? currentSignal.height : currentSignal.width;
            int height = outputRotation == 90 || outputRotation == 270
                    ? currentSignal.width : currentSignal.height;
            Toast.makeText(this, "预览与录制已旋转 " + outputRotation + "°，录制输出 "
                    + width + "×" + height, Toast.LENGTH_SHORT).show();
        }
    }

    private void releaseCamera() {
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
        if (phoneCameraSource != null) {
            if (hdmiOutputController != null) {
                hdmiOutputController.detachLiveSource(phoneCameraSource);
            }
            phoneCameraSource.release();
            phoneCameraSource = null;
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

    private final PhoneCameraSource.Listener phoneCameraListener =
            new PhoneCameraSource.Listener() {
        @Override
        public void onOpened(List<Size> modes) {
            if (phoneCameraSource == null || currentInput == null
                    || !currentInput.isPhoneCamera()) return;
            supportedSizes = uniqueSizes(modes);
            supportedFormats = Collections.emptyList();
            if (supportedSizes.isEmpty()) {
                noSignal.setText("该手机摄像头没有可用的 4K、1080P 或 720P 输出");
                noSignal.setVisibility(View.VISIBLE);
                return;
            }
            AppSettings.SavedSignalMode saved = AppSettings.getSignalMode(
                    MainActivity.this, currentInput.stableKey);
            fallbackModes = buildFallbackModes(supportedSizes, saved);
            fallbackIndex = 0;
            bandwidthIndex = 0;
            deviceInfo.setText(currentInput.label);
            signalModeButton.setEnabled(true);
            applyPhonePreviewMode(fallbackModes.get(0));
        }

        @Override
        public void onPreviewConfigured(Size mode) {
            // The per-request callback below owns the state transition.
        }

        @Override
        public void onClosed() {
            previewReady = false;
            mainRecordButton.setEnabled(false);
            noSignal.setText("手机摄像头已关闭");
            noSignal.setVisibility(View.VISIBLE);
        }

        @Override
        public void onError(Throwable error) {
            previewReady = false;
            mainRecordButton.setEnabled(false);
            noSignal.setText("无法打开手机摄像头：" + readableMessage(error));
            noSignal.setVisibility(View.VISIBLE);
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
        if (phoneCameraSource != null) {
            applyPhonePreviewMode(mode);
            return;
        }
        if (cameraSource == null || !cameraSource.isOpened()) return;
        if (isBusy()) {
            Toast.makeText(this, "录制中不能切换输入格式", Toast.LENGTH_SHORT).show();
            return;
        }
        showDirectPreview();
        if (!cameraView.getHolder().getSurface().isValid()) {
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
        AtomicInteger frameCount = new AtomicInteger();
        cameraSource.startPreview(mode, bandwidth, cameraView.getHolder().getSurface(), frame -> {
            if (generation != previewGeneration) return;
            if (frameCount.incrementAndGet() == REQUIRED_TEST_FRAMES) {
                runOnUiThread(() -> confirmPreviewMode(
                        generation, mode, bandwidth, allowFallback));
            }
        }, new DirectUvcCameraSource.PreviewCallback() {
            @Override
            public void onConfigured() {
                if (generation == previewGeneration) noSignal.setText("等待 UVC 视频帧…");
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

    private void applyPhonePreviewMode(Size mode) {
        if (phoneCameraSource == null) return;
        if (isBusy()) {
            Toast.makeText(this, "录制中不能切换手机摄像头分辨率", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedMode = mode.clone();
        layoutPhonePreview(mode);
        SurfaceTexture texture = gpuCameraView.getSurfaceTexture();
        if (texture != null) texture.setDefaultBufferSize(mode.width, mode.height);
        if (gpuScreenSurface == null || !gpuScreenSurface.isValid()) {
            noSignal.setText("正在等待手机摄像头预览画布…");
            noSignal.setVisibility(View.VISIBLE);
            return;
        }
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        int generation = ++previewGeneration;
        previewReady = false;
        currentSignal = SignalInfo.from(mode, Collections.emptyList());
        cameraView.setVisibility(View.GONE);
        gpuCameraView.setVisibility(View.VISIBLE);
        gpuCameraView.setAlpha(1f);
        signalInfo.setText("正在打开  " + currentSignal.displayText());
        noSignal.setText("正在配置手机 Camera2 摄像头…");
        noSignal.setVisibility(View.VISIBLE);
        mainRecordButton.setEnabled(false);
        phoneCameraSource.startPreview(mode, gpuScreenSurface,
                new PhoneCameraSource.PreviewCallback() {
                    @Override
                    public void onConfigured() {
                        if (generation == previewGeneration) {
                            completePhonePreview(generation, mode);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (generation != previewGeneration) return;
                        noSignal.setText("手机摄像头分辨率不可用：" + readableMessage(error));
                        noSignal.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void completePhonePreview(int generation, Size mode) {
        if (generation != previewGeneration || phoneCameraSource == null) return;
        currentSignal = SignalInfo.from(mode, Collections.emptyList());
        updateSignalSummary();
        previewReady = true;
        noSignal.setVisibility(View.GONE);
        mainRecordButton.setEnabled(true);
        recordingController = new RecordingController(this, phoneCameraSource,
                currentSignal, this);
        if (hdmiOutputController != null
                && hdmiOutputController.getMode() == HdmiOutputController.Mode.LIVE) {
            hdmiOutputController.attachLiveSource(phoneCameraSource);
        }
        if (currentInput != null) {
            AppSettings.saveSignalMode(this, currentInput.stableKey, selectedMode, 0);
        }
        refreshSettingsUi();
        refreshAudioLevelMonitor();
    }

    private void layoutPhonePreview(Size mode) {
        if (mode == null || currentInput == null || !currentInput.isPhoneCamera()) return;
        gpuCameraView.post(() -> {
            View parent = gpuCameraView.getParent() instanceof View
                    ? (View) gpuCameraView.getParent() : null;
            int parentWidth = parent == null ? gpuCameraView.getWidth() : parent.getWidth();
            int parentHeight = parent == null ? gpuCameraView.getHeight() : parent.getHeight();
            if (parentWidth <= 0 || parentHeight <= 0) return;
            int relative = PhoneCameraCatalog.relativeRotation(this, currentInput);
            boolean quarterTurn = relative == 90 || relative == 270;
            int footprintWidth = quarterTurn ? mode.height : mode.width;
            int footprintHeight = quarterTurn ? mode.width : mode.height;
            // Single-device preview is the full-screen monitor. Use center-crop after
            // rotation: fill the screen without changing X/Y proportions, and crop only
            // the excess edge instead of squeezing a portrait frame into a narrow strip.
            float scale = Math.max(parentWidth / (float) Math.max(1, footprintWidth),
                    parentHeight / (float) Math.max(1, footprintHeight));
            int finalWidth = Math.max(1, Math.round(footprintWidth * scale));
            int finalHeight = Math.max(1, Math.round(footprintHeight * scale));
            int layoutWidth = quarterTurn ? finalHeight : finalWidth;
            int layoutHeight = quarterTurn ? finalWidth : finalHeight;
            ViewGroup.LayoutParams existing = gpuCameraView.getLayoutParams();
            FrameLayout.LayoutParams params = existing instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) existing
                    : new FrameLayout.LayoutParams(layoutWidth, layoutHeight, Gravity.CENTER);
            params.width = layoutWidth;
            params.height = layoutHeight;
            params.gravity = Gravity.CENTER;
            gpuCameraView.setLayoutParams(params);
            gpuCameraView.setPivotX(layoutWidth / 2f);
            gpuCameraView.setPivotY(layoutHeight / 2f);
            gpuCameraView.setRotation(relative);
            boolean mirror = PhoneCameraCatalog.isFrontFacing(this, currentInput);
            // After a quarter turn, local X is the screen's vertical axis. Mirror local Y
            // so the front camera still mirrors left/right on screen rather than top/bottom.
            gpuCameraView.setScaleX(mirror && !quarterTurn ? -1f : 1f);
            gpuCameraView.setScaleY(mirror && quarterTurn ? -1f : 1f);
        });
    }

    private void resetPhonePreviewTransform() {
        ViewGroup.LayoutParams existing = gpuCameraView.getLayoutParams();
        if (existing instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) existing;
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.CENTER;
            gpuCameraView.setLayoutParams(params);
        }
        gpuCameraView.setRotation(0f);
        gpuCameraView.setScaleX(1f);
        gpuCameraView.setScaleY(1f);
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
        AppSettings.saveSignalMode(this, currentDevice, selectedMode, bandwidthIndex);
        healthHandler.removeCallbacks(previewHealthCheck);
        healthHandler.postDelayed(previewHealthCheck, 2_000);
    }

    private void updateSignalSummary() {
        if (currentSignal == null) return;
        String rotationText = outputRotation == 0 ? "" : " / 旋转 " + outputRotation + "°";
        if (phoneCameraSource != null) {
            signalInfo.setText(currentSignal.displayText() + " / 手机摄像头");
        } else {
            signalInfo.setText(currentSignal.displayText() + " / USB "
                    + Math.round(BANDWIDTH_FACTORS[bandwidthIndex] * 100) + "%" + rotationText);
        }
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
                .setTitle(phoneCameraSource != null ? "选择手机摄像头分辨率与帧率"
                        : "选择 UVC 输入格式（黑屏优先选 MJPEG）")
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
            if (cameraSource != null && gpuScreenSurface != null && previewReady) {
                cameraSource.setScreenSurface(gpuScreenSurface,
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
        String audioText = phoneCameraSource != null ? "手机麦克风"
                : (AppSettings.isUsbAudioEnabled(this) ? "UAC" : "无音频");
        String formatText = container.label + " / " + codec.label
                + " / " + audioText;
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
        if (AppSettings.isUsbAudioEnabled(this) && hasAudioPermission()) {
            if (previewReady && cameraSource != null) {
                source = cameraSource;
            } else if (previewReady && phoneCameraSource != null) {
                source = phoneCameraSource;
            }
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
        String audioText = phoneCameraSource != null ? "手机麦克风"
                : (AppSettings.isUsbAudioEnabled(this) ? "UAC" : "无音频");
        String formatText = container.label + " / " + codec.label
                + " / " + audioText;
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
