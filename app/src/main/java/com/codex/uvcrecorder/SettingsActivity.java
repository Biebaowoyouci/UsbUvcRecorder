package com.codex.uvcrecorder;

import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

public final class SettingsActivity extends AppCompatActivity {
    private static final int[] BITRATE_VALUES = {0, 8, 15, 25, 50, 80, 120};
    private static final int[] SEGMENT_VALUES = {0, 1, 5, 10, 30, 60};
    private static final int[] RTMP_BITRATE_VALUES = {4, 6, 8, 12, 20, 30, 50};
    private static final int[] RTMP_HEIGHT_VALUES = {1080, 720, 0};
    private TextView storageValue;
    private Button sharedAudioButton;
    private SwitchMaterial audioSwitch;
    private SwitchMaterial rtmpEnabledSwitch;
    private SwitchMaterial rtmpAudioSwitch;
    private EditText rtmpUrlInput;
    private TextView rtmpStatus;
    private Spinner rtmpBitrateSpinner;
    private Spinner rtmpResolutionSpinner;
    private boolean updatingRtmp;
    private SwitchMaterial pullEnabledSwitch;
    private EditText pullUrlInput;
    private TextView pullStatus;
    private boolean updatingPull;
    private Button inputModeUvcButton;
    private Button inputModeCameraButton;
    private TextView inputModeStatus;

    private final ActivityResultLauncher<Uri> chooseTree = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onTreeSelected);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBarInsets.apply(this, findViewById(R.id.settings_root));

        findViewById(R.id.back_button).setOnClickListener(v -> {
            persistRtmp(false);
            persistPull(false);
            finish();
        });
        storageValue = findViewById(R.id.storage_value);
        setupInputMode();
        setupRtmp();
        setupPull();
        refreshSystemCameraStatus();
        setupContainer();
        setupVideoCodec();
        setupUsbAudio();
        setupSharedAudio();
        setupBitrate();
        setupSegment();
        setupDual();
        setupButtonOpacity();
        refreshStorage();

        findViewById(R.id.files_settings_button).setOnClickListener(v ->
                startActivity(new Intent(this, RecordingsActivity.class)));

        findViewById(R.id.choose_storage_button).setOnClickListener(v ->
                chooseTree.launch(AppSettings.getTreeUri(this)));
        findViewById(R.id.reset_storage_button).setOnClickListener(v -> {
            AppSettings.clearTree(this);
            refreshStorage();
        });
        applyButtonAppearance();
    }

    private void setupInputMode() {
        inputModeUvcButton = findViewById(R.id.input_mode_uvc_button);
        inputModeCameraButton = findViewById(R.id.input_mode_camera_button);
        inputModeStatus = findViewById(R.id.input_mode_status);
        inputModeUvcButton.setOnClickListener(v ->
                selectInputMode(AppSettings.InputMode.UVC));
        inputModeCameraButton.setOnClickListener(v ->
                selectInputMode(AppSettings.InputMode.CAMERA));
        refreshInputMode();
    }

    private void selectInputMode(AppSettings.InputMode mode) {
        AppSettings.setInputMode(this, mode);
        refreshInputMode();
        Toast.makeText(this, mode == AppSettings.InputMode.CAMERA
                        ? "已切换到相机模式，返回预览后生效"
                        : "已切换到 UVC 模式，返回预览后生效",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshInputMode() {
        boolean camera = AppSettings.getInputMode(this) == AppSettings.InputMode.CAMERA;
        inputModeUvcButton.setSelected(!camera);
        inputModeCameraButton.setSelected(camera);
        inputModeStatus.setText(camera
                ? R.string.input_mode_camera_status : R.string.input_mode_uvc_status);
    }

    @Override
    protected void onPause() {
        persistRtmp(false);
        persistPull(false);
        super.onPause();
    }

    private void setupRtmp() {
        rtmpEnabledSwitch = findViewById(R.id.rtmp_enabled_switch);
        rtmpAudioSwitch = findViewById(R.id.rtmp_audio_switch);
        rtmpUrlInput = findViewById(R.id.rtmp_url_input);
        rtmpStatus = findViewById(R.id.rtmp_settings_status);
        rtmpBitrateSpinner = findViewById(R.id.rtmp_bitrate_spinner);
        rtmpResolutionSpinner = findViewById(R.id.rtmp_resolution_spinner);

        rtmpUrlInput.setText(AppSettings.getRtmpUrl(this));
        rtmpAudioSwitch.setChecked(AppSettings.isRtmpAudioEnabled(this));
        rtmpBitrateSpinner.setAdapter(adapter(new String[]{
                "4 Mbps", "6 Mbps", "8 Mbps", "12 Mbps", "20 Mbps", "30 Mbps", "50 Mbps"
        }));
        rtmpBitrateSpinner.setSelection(indexOf(RTMP_BITRATE_VALUES,
                AppSettings.getRtmpBitrateMbps(this)));
        rtmpResolutionSpinner.setAdapter(adapter(new String[]{
                "最高 1080p（推荐）", "最高 720p（弱网）", "跟随输入信号"
        }));
        rtmpResolutionSpinner.setSelection(indexOf(RTMP_HEIGHT_VALUES,
                AppSettings.getRtmpMaxHeight(this)));
        rtmpEnabledSwitch.setChecked(AppSettings.isRtmpEnabled(this));
        refreshRtmpStatus();

        rtmpEnabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingRtmp) persistRtmp(true);
        });
        rtmpAudioSwitch.setOnCheckedChangeListener((button, checked) ->
                AppSettings.setRtmpAudioEnabled(this, checked));
        rtmpBitrateSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                AppSettings.setRtmpBitrateMbps(this, RTMP_BITRATE_VALUES[position])));
        rtmpResolutionSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                AppSettings.setRtmpMaxHeight(this, RTMP_HEIGHT_VALUES[position])));
        findViewById(R.id.rtmp_save_button).setOnClickListener(v -> persistRtmp(true));
    }

    private boolean persistRtmp(boolean showToast) {
        if (rtmpUrlInput == null) return true;
        String url = rtmpUrlInput.getText().toString().trim();
        AppSettings.setRtmpUrl(this, url);
        AppSettings.setRtmpAudioEnabled(this, rtmpAudioSwitch.isChecked());
        AppSettings.setRtmpBitrateMbps(this,
                RTMP_BITRATE_VALUES[rtmpBitrateSpinner.getSelectedItemPosition()]);
        AppSettings.setRtmpMaxHeight(this,
                RTMP_HEIGHT_VALUES[rtmpResolutionSpinner.getSelectedItemPosition()]);
        boolean enabled = rtmpEnabledSwitch.isChecked();
        if (enabled && !isValidRtmpUrl(url)) {
            AppSettings.setRtmpEnabled(this, false);
            updatingRtmp = true;
            rtmpEnabledSwitch.setChecked(false);
            updatingRtmp = false;
            rtmpStatus.setText(R.string.rtmp_url_invalid);
            if (showToast) {
                Toast.makeText(this, R.string.rtmp_url_invalid, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        AppSettings.setRtmpEnabled(this, enabled);
        refreshRtmpStatus();
        if (showToast) Toast.makeText(this, R.string.rtmp_saved, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void refreshRtmpStatus() {
        rtmpStatus.setText(rtmpEnabledSwitch.isChecked()
                ? R.string.rtmp_enabled_status : R.string.rtmp_disabled_status);
    }

    static boolean isValidRtmpUrl(String url) {
        if (url == null) return false;
        String value = url.trim().toLowerCase();
        if (!value.startsWith("rtmp://") && !value.startsWith("rtmps://")) return false;
        int schemeEnd = value.indexOf("://") + 3;
        return value.length() > schemeEnd && value.indexOf('/', schemeEnd) > schemeEnd;
    }

    private void setupPull() {
        pullEnabledSwitch = findViewById(R.id.pull_enabled_switch);
        pullUrlInput = findViewById(R.id.pull_url_input);
        pullStatus = findViewById(R.id.pull_settings_status);
        pullUrlInput.setText(AppSettings.getPullUrl(this));
        pullEnabledSwitch.setChecked(AppSettings.isPullEnabled(this));
        refreshPullStatus();
        pullEnabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingPull) persistPull(true);
        });
        findViewById(R.id.pull_save_button).setOnClickListener(v -> persistPull(true));
    }

    private boolean persistPull(boolean showToast) {
        if (pullUrlInput == null) return true;
        String url = pullUrlInput.getText().toString().trim();
        AppSettings.setPullUrl(this, url);
        boolean enabled = pullEnabledSwitch.isChecked();
        if (enabled && !isValidPullUrl(url)) {
            AppSettings.setPullEnabled(this, false);
            updatingPull = true;
            pullEnabledSwitch.setChecked(false);
            updatingPull = false;
            pullStatus.setText(R.string.pull_url_invalid);
            if (showToast) {
                Toast.makeText(this, R.string.pull_url_invalid, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        AppSettings.setPullEnabled(this, enabled);
        refreshPullStatus();
        if (showToast) Toast.makeText(this, R.string.pull_saved, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void refreshPullStatus() {
        pullStatus.setText(pullEnabledSwitch.isChecked()
                ? R.string.pull_enabled_status : R.string.pull_disabled_status);
    }

    private void refreshSystemCameraStatus() {
        TextView status = findViewById(R.id.system_camera_status);
        List<String> externalLabels = new ArrayList<>();
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager != null) {
            try {
                for (String id : manager.getCameraIdList()) {
                    Integer facing = manager.getCameraCharacteristics(id).get(
                            CameraCharacteristics.LENS_FACING);
                    if (facing != null
                            && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                        externalLabels.add("Camera2 ID " + id);
                    }
                }
            } catch (Exception ignored) {
                externalLabels.clear();
            }
        }
        if (externalLabels.isEmpty()) {
            status.setText(R.string.system_camera_unavailable);
        } else {
            status.setText(getString(R.string.system_camera_available,
                    externalLabels.size(), android.text.TextUtils.join("、", externalLabels)));
        }
    }

    static boolean isValidPullUrl(String url) {
        if (url == null) return false;
        String value = url.trim().toLowerCase();
        boolean supportedScheme = value.startsWith("rtmp://")
                || value.startsWith("http://") || value.startsWith("https://");
        if (!supportedScheme) return false;
        int schemeEnd = value.indexOf("://") + 3;
        return value.length() > schemeEnd && value.indexOf('/', schemeEnd) > schemeEnd;
    }

    private void setupContainer() {
        Spinner spinner = findViewById(R.id.container_spinner);
        String[] labels = {"MP4（通用）", "MOV（QuickTime 兼容）", "M4V", "AVI（无音频）"};
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(AppSettings.getContainer(this).ordinal());
        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                AppSettings.setContainer(this, AppSettings.Container.values()[position])));
    }

    private void setupVideoCodec() {
        Spinner spinner = findViewById(R.id.video_codec_spinner);
        String[] labels = {"H.264 / AVC（兼容性优先）", "H.265 / HEVC（更高压缩率）"};
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(AppSettings.getVideoCodec(this).ordinal());
        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                AppSettings.setVideoCodec(this, AppSettings.VideoCodec.values()[position])));
    }

    private void setupUsbAudio() {
        audioSwitch = findViewById(R.id.usb_audio_switch);
        audioSwitch.setChecked(AppSettings.isUsbAudioEnabled(this));
        audioSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            AppSettings.setUsbAudioEnabled(this, checked);
            refreshSharedAudioButton();
        });
    }

    private void setupSharedAudio() {
        sharedAudioButton = findViewById(R.id.shared_audio_button);
        sharedAudioButton.setOnClickListener(v -> showSharedAudioDialog());
        refreshSharedAudioButton();
    }

    private void refreshSharedAudioButton() {
        if (sharedAudioButton == null) return;
        boolean enabled = audioSwitch == null || audioSwitch.isChecked();
        sharedAudioButton.setEnabled(enabled);
        if (AppSettings.isMultiAudioShareEnabled(this)
                && !AppSettings.getMultiAudioSourceKey(this).isEmpty()) {
            sharedAudioButton.setText(getString(R.string.multi_audio_share_value,
                    AppSettings.getMultiAudioSourceLabel(this)));
        } else {
            sharedAudioButton.setText(R.string.multi_audio_share_off);
        }
    }

    private void showSharedAudioDialog() {
        List<UsbDevice> inputs = UsbDeviceCatalog.listVideoInputs(this);
        List<VideoChoice> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        choices.add(null);
        labels.add(getString(R.string.multi_audio_share_disable));

        boolean sharing = AppSettings.isMultiAudioShareEnabled(this)
                && !AppSettings.getMultiAudioSourceKey(this).isEmpty();
        String savedKey = AppSettings.getMultiAudioSourceKey(this);
        String savedLabel = AppSettings.getMultiAudioSourceLabel(this);
        int checked = sharing ? -1 : 0;
        for (UsbDevice input : inputs) {
            String key = UsbDeviceCatalog.stableKey(input);
            String label = UsbDeviceCatalog.label(input);
            VideoChoice choice = new VideoChoice(key, label);
            choices.add(choice);
            labels.add(label);
            if (sharing && key.equals(savedKey)) {
                checked = choices.size() - 1;
            }
        }
        if (sharing && checked < 0 && !savedKey.isEmpty() && !savedLabel.isEmpty()) {
            choices.add(new VideoChoice(savedKey, savedLabel));
            labels.add(getString(R.string.multi_audio_share_saved_offline, savedLabel));
            checked = choices.size() - 1;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_audio_share_dialog)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            VideoChoice choice = choices.get(which);
                            if (choice == null) {
                                AppSettings.disableMultiAudioShare(this);
                            } else {
                                AppSettings.setMultiAudioShare(this,
                                        choice.deviceKey, choice.deviceLabel);
                            }
                            refreshSharedAudioButton();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setupBitrate() {
        Spinner spinner = findViewById(R.id.bitrate_spinner);
        String[] labels = {"自动（按输入信号）", "8 Mbps", "15 Mbps", "25 Mbps", "50 Mbps",
                "80 Mbps", "120 Mbps"};
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(indexOf(BITRATE_VALUES, AppSettings.getBitrateMbps(this)));
        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                AppSettings.setBitrateMbps(this, BITRATE_VALUES[position])));
    }

    private void setupSegment() {
        Spinner spinner = findViewById(R.id.segment_spinner);
        String[] labels = {"无（不启用）", "1 分钟", "5 分钟", "10 分钟", "30 分钟", "60 分钟"};
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(indexOf(SEGMENT_VALUES, AppSettings.getSegmentMinutes(this)));
        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                AppSettings.setSegmentMinutes(this, SEGMENT_VALUES[position])));
    }

    private void setupDual() {
        SwitchMaterial dual = findViewById(R.id.dual_switch);
        dual.setChecked(AppSettings.isDualEnabled(this));
        dual.setOnCheckedChangeListener((buttonView, checked) ->
                AppSettings.setDualEnabled(this, checked));
    }

    private void setupButtonOpacity() {
        SeekBar seekBar = findViewById(R.id.button_opacity_seekbar);
        TextView value = findViewById(R.id.button_opacity_value);
        int saved = AppSettings.getButtonOpacity(this);
        seekBar.setProgress(saved);
        value.setText(getString(R.string.button_opacity_value, saved));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int opacity = Math.max(20, progress);
                value.setText(getString(R.string.button_opacity_value, opacity));
                if (fromUser) {
                    AppSettings.setButtonOpacity(SettingsActivity.this, opacity);
                    applyButtonAppearance();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }

            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private void applyButtonAppearance() {
        ButtonAppearance.apply(findViewById(R.id.settings_root),
                AppSettings.getButtonOpacity(this));
    }

    private ArrayAdapter<String> adapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item, values);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        return adapter;
    }

    private void onTreeSelected(Uri uri) {
        if (uri == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.canWrite()) throw new IllegalStateException("目录不可写");
            String label = tree.getName() == null ? uri.getLastPathSegment() : tree.getName();
            AppSettings.setTree(this, uri, label);
            refreshStorage();
        } catch (Exception error) {
            Toast.makeText(this, "无法使用该目录：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStorage() {
        storageValue.setText(AppSettings.getStorageLabel(this));
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return 0;
    }

    private static final class VideoChoice {
        final String deviceKey;
        final String deviceLabel;

        VideoChoice(String deviceKey, String deviceLabel) {
            this.deviceKey = deviceKey;
            this.deviceLabel = deviceLabel;
        }
    }
}
