package com.codex.uvcrecorder;

import android.content.Context;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.util.Rational;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** TVU-style in-preview camera controls: one side tab and one adjustment at a time. */
final class PhoneCameraSideControls {
    interface Host {
        void cycleCamera();

        void showCameraMode();

        void toggleRecording();

        void toggleTransmission();

        void openSettings();

        void microphoneChanged();

        void cameraPanelChanged();
    }

    private final Context context;
    private final Host host;
    private final View root;
    private final View adjustment;
    private final TextView title;
    private final Spinner spinner;
    private final TextView valueOne;
    private final TextView valueTwo;
    private final SeekBar seekOne;
    private final SeekBar seekTwo;
    private final Button action;
    private final Button record;
    private final Button transmit;
    private PhoneCameraCatalog.Device device;
    private PhoneCameraCatalog.Mode mode;
    private PhoneCameraSource source;
    private boolean updating;

    PhoneCameraSideControls(View activityRoot, Context context, Host host) {
        this.context = context;
        this.host = host;
        root = activityRoot.findViewById(R.id.camera_mode_panel);
        adjustment = activityRoot.findViewById(R.id.camera_adjustment_panel);
        title = activityRoot.findViewById(R.id.camera_adjustment_title);
        spinner = activityRoot.findViewById(R.id.camera_adjustment_spinner);
        valueOne = activityRoot.findViewById(R.id.camera_adjustment_value_one);
        valueTwo = activityRoot.findViewById(R.id.camera_adjustment_value_two);
        seekOne = activityRoot.findViewById(R.id.camera_adjustment_seek_one);
        seekTwo = activityRoot.findViewById(R.id.camera_adjustment_seek_two);
        action = activityRoot.findViewById(R.id.camera_adjustment_action);
        record = activityRoot.findViewById(R.id.camera_record_quick);
        transmit = activityRoot.findViewById(R.id.camera_transmit_quick);

        activityRoot.findViewById(R.id.camera_focus_quick)
                .setOnClickListener(ignored -> showFocus());
        activityRoot.findViewById(R.id.camera_zoom_quick)
                .setOnClickListener(ignored -> showZoom());
        activityRoot.findViewById(R.id.camera_brightness_quick)
                .setOnClickListener(ignored -> showBrightness());
        activityRoot.findViewById(R.id.camera_awb_quick)
                .setOnClickListener(ignored -> showAwb());
        activityRoot.findViewById(R.id.camera_beauty_quick)
                .setOnClickListener(ignored -> showBeauty());
        activityRoot.findViewById(R.id.camera_exposure_quick)
                .setOnClickListener(ignored -> showExposure());
        activityRoot.findViewById(R.id.camera_microphone_quick)
                .setOnClickListener(ignored -> showMicrophone());
        activityRoot.findViewById(R.id.camera_frame_quick)
                .setOnClickListener(ignored -> showFrameMode());
        activityRoot.findViewById(R.id.camera_format_quick)
                .setOnClickListener(ignored -> host.showCameraMode());
        activityRoot.findViewById(R.id.camera_switch_quick)
                .setOnClickListener(ignored -> host.cycleCamera());
        transmit.setOnClickListener(ignored -> host.toggleTransmission());
        record.setOnClickListener(ignored -> host.toggleRecording());
        activityRoot.findViewById(R.id.camera_settings_quick)
                .setOnClickListener(ignored -> host.openSettings());
        activityRoot.findViewById(R.id.camera_adjustment_close)
                .setOnClickListener(ignored -> closeAdjustment());
    }

    void bind(PhoneCameraCatalog.Device nextDevice, PhoneCameraCatalog.Mode nextMode,
              PhoneCameraSource nextSource) {
        device = nextDevice;
        mode = nextMode;
        source = nextSource;
        closeAdjustment();
        refreshTransmit();
    }

    void clear() {
        source = null;
        device = null;
        mode = null;
        closeAdjustment();
    }

    void setVisible(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) closeAdjustment();
        host.cameraPanelChanged();
    }

    boolean isVisible() {
        return root.getVisibility() == View.VISIBLE;
    }

    boolean isAdjustmentVisible() {
        return adjustment.getVisibility() == View.VISIBLE;
    }

    void setRecording(boolean active) {
        record.setSelected(active);
        record.setText(active ? "停止" : "录制");
    }

    void refreshTransmit() {
        boolean enabled = AppSettings.isRtmpEnabled(context);
        transmit.setSelected(enabled);
        transmit.setText(enabled ? "停止传输" : "传输");
    }

    private boolean ready() {
        return source != null && device != null && mode != null;
    }

    private void prepare(String panelTitle) {
        if (!ready()) return;
        updating = true;
        title.setText(panelTitle);
        spinner.setVisibility(View.GONE);
        valueOne.setVisibility(View.GONE);
        valueTwo.setVisibility(View.GONE);
        seekOne.setVisibility(View.GONE);
        seekTwo.setVisibility(View.GONE);
        action.setVisibility(View.GONE);
        spinner.setOnItemSelectedListener(null);
        seekOne.setOnSeekBarChangeListener(null);
        seekTwo.setOnSeekBarChangeListener(null);
        action.setOnClickListener(null);
        adjustment.setVisibility(View.VISIBLE);
        host.cameraPanelChanged();
    }

    private void finishPrepare() {
        updating = false;
    }

    private void closeAdjustment() {
        adjustment.setVisibility(View.GONE);
        host.cameraPanelChanged();
    }

    private void apply(PhoneCameraControls.State state) {
        if (!updating && source != null) source.updateControls(state);
    }

    private void showAwb() {
        prepare("白平衡");
        List<Integer> modes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int value : device.capabilities.awbModes) {
            if (value == CaptureRequest.CONTROL_AWB_MODE_OFF) continue;
            modes.add(value);
            labels.add(awbLabel(value));
        }
        if (modes.isEmpty()) {
            modes.add(CaptureRequest.CONTROL_AWB_MODE_AUTO);
            labels.add("自动");
        }
        PhoneCameraControls.State state = source.controls();
        spinner.setVisibility(View.VISIBLE);
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(Math.max(0, modes.indexOf(state.awbMode)));
        spinner.setOnItemSelectedListener(selection(position -> {
            PhoneCameraControls.State next = source.controls();
            next.awbMode = modes.get(position);
            apply(next);
        }));
        finishPrepare();
    }

    private void showBrightness() {
        prepare("亮度");
        PhoneCameraControls.State state = source.controls();
        Range<Integer> range = device.capabilities.exposureCompensationRange;
        int minimum = range == null ? 0 : range.getLower();
        int maximum = range == null ? 0 : range.getUpper();
        valueOne.setVisibility(View.VISIBLE);
        seekOne.setVisibility(View.VISIBLE);
        seekOne.setMax(Math.max(0, maximum - minimum));
        seekOne.setProgress(state.exposureCompensation - minimum);
        updateBrightnessLabel(state.exposureCompensation);
        seekOne.setOnSeekBarChangeListener(seek(progress -> {
            PhoneCameraControls.State next = source.controls();
            next.exposureCompensation = minimum + progress;
            updateBrightnessLabel(next.exposureCompensation);
            apply(next);
        }));
        finishPrepare();
    }

    private void updateBrightnessLabel(int compensation) {
        Rational step = device.capabilities.exposureCompensationStep;
        float ev = step == null ? compensation : compensation * step.floatValue();
        valueOne.setText(String.format(Locale.CHINA, "%+.1f EV", ev));
    }

    private void showZoom() {
        prepare("缩放");
        PhoneCameraControls.State state = source.controls();
        float minimum = device.capabilities.minimumZoom();
        float maximum = device.capabilities.maximumZoom();
        valueOne.setVisibility(View.VISIBLE);
        seekOne.setVisibility(View.VISIBLE);
        seekOne.setMax(1000);
        seekOne.setProgress(maximum <= minimum ? 0
                : Math.round((state.zoomRatio - minimum) * 1000f / (maximum - minimum)));
        updateZoomLabel(state.zoomRatio);
        seekOne.setOnSeekBarChangeListener(seek(progress -> {
            PhoneCameraControls.State next = source.controls();
            next.zoomRatio = minimum + (maximum - minimum) * progress / 1000f;
            updateZoomLabel(next.zoomRatio);
            apply(next);
        }));
        finishPrepare();
    }

    private void updateZoomLabel(float ratio) {
        valueOne.setText(String.format(Locale.CHINA, "%.2f×", ratio));
    }

    private void showBeauty() {
        prepare("美颜");
        PhoneCameraControls.State state = source.controls();
        valueOne.setVisibility(View.VISIBLE);
        seekOne.setVisibility(View.VISIBLE);
        seekOne.setMax(100);
        seekOne.setProgress(state.beauty);
        updateBeautyLabel(state.beauty);
        seekOne.setOnSeekBarChangeListener(seek(progress -> {
            PhoneCameraControls.State next = source.controls();
            next.beauty = progress;
            updateBeautyLabel(progress);
            apply(next);
        }));
        finishPrepare();
    }

    private void updateBeautyLabel(int level) {
        valueOne.setText(level == 0 ? "关闭" : level + "%");
    }

    private void showFocus() {
        prepare("对焦");
        List<Integer> modes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int value : device.capabilities.afModes) {
            modes.add(value);
            labels.add(afLabel(value));
        }
        PhoneCameraControls.State state = source.controls();
        spinner.setVisibility(View.VISIBLE);
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(Math.max(0, modes.indexOf(state.afMode)));
        valueOne.setVisibility(View.VISIBLE);
        seekOne.setVisibility(View.VISIBLE);
        seekOne.setMax(1000);
        seekOne.setProgress(device.capabilities.minimumFocusDistance <= 0f ? 0
                : Math.round(state.focusDistance * 1000f
                / device.capabilities.minimumFocusDistance));
        refreshFocusUi(state);
        spinner.setOnItemSelectedListener(selection(position -> {
            PhoneCameraControls.State next = source.controls();
            next.afMode = modes.get(position);
            refreshFocusUi(next);
            apply(next);
        }));
        seekOne.setOnSeekBarChangeListener(seek(progress -> {
            PhoneCameraControls.State next = source.controls();
            next.focusDistance = device.capabilities.minimumFocusDistance
                    * progress / 1000f;
            refreshFocusUi(next);
            apply(next);
        }));
        action.setVisibility(View.VISIBLE);
        action.setText("立即对焦一次");
        action.setOnClickListener(ignored -> source.triggerAutoFocus());
        finishPrepare();
    }

    private void refreshFocusUi(PhoneCameraControls.State state) {
        boolean manual = state.afMode == CaptureRequest.CONTROL_AF_MODE_OFF
                && device.capabilities.minimumFocusDistance > 0f;
        seekOne.setEnabled(manual);
        valueOne.setAlpha(manual ? 1f : 0.45f);
        valueOne.setText(state.focusDistance <= 0.001f ? "焦距：∞"
                : String.format(Locale.CHINA, "焦距：%.2f D", state.focusDistance));
    }

    private void showExposure() {
        prepare("曝光");
        PhoneCameraControls.State state = source.controls();
        List<String> choices = new ArrayList<>();
        choices.add("自动曝光");
        if (device.capabilities.supportsManualExposure()) choices.add("手动 ISO / 快门");
        spinner.setVisibility(View.VISIBLE);
        spinner.setAdapter(adapter(choices));
        spinner.setSelection(state.autoExposure ? 0 : 1);
        valueOne.setVisibility(View.VISIBLE);
        seekOne.setVisibility(View.VISIBLE);
        valueTwo.setVisibility(View.VISIBLE);
        seekTwo.setVisibility(View.VISIBLE);
        seekOne.setMax(100);
        seekTwo.setMax(100);
        Range<Integer> iso = device.capabilities.sensitivityRange;
        Range<Long> exposure = device.capabilities.exposureTimeRange;
        if (iso != null) {
            seekOne.setProgress(PhoneCameraControls.longToProgress(
                    state.iso, iso.getLower(), iso.getUpper()));
        }
        long videoMaximum = exposure == null ? 1L : Math.max(exposure.getLower(),
                Math.min(exposure.getUpper(), 1_000_000_000L / Math.max(1, mode.fps)));
        if (exposure != null) {
            seekTwo.setProgress(PhoneCameraControls.longToProgress(
                    state.exposureTimeNs, exposure.getLower(), videoMaximum));
        }
        refreshExposureUi(state);
        spinner.setOnItemSelectedListener(selection(position -> {
            PhoneCameraControls.State next = source.controls();
            next.autoExposure = position == 0;
            refreshExposureUi(next);
            apply(next);
        }));
        seekOne.setOnSeekBarChangeListener(seek(progress -> {
            if (iso == null) return;
            PhoneCameraControls.State next = source.controls();
            next.iso = (int) PhoneCameraControls.progressToLong(
                    progress, iso.getLower(), iso.getUpper());
            refreshExposureUi(next);
            apply(next);
        }));
        seekTwo.setOnSeekBarChangeListener(seek(progress -> {
            if (exposure == null) return;
            PhoneCameraControls.State next = source.controls();
            next.exposureTimeNs = PhoneCameraControls.progressToLong(
                    progress, exposure.getLower(), videoMaximum);
            refreshExposureUi(next);
            apply(next);
        }));
        finishPrepare();
    }

    private void refreshExposureUi(PhoneCameraControls.State state) {
        boolean manual = !state.autoExposure
                && device.capabilities.supportsManualExposure();
        seekOne.setEnabled(manual);
        seekTwo.setEnabled(manual);
        valueOne.setAlpha(manual ? 1f : 0.45f);
        valueTwo.setAlpha(manual ? 1f : 0.45f);
        valueOne.setText("ISO " + state.iso);
        valueTwo.setText("快门 " + shutterLabel(state.exposureTimeNs));
    }

    private void showMicrophone() {
        prepare("外接麦克风");
        List<PhoneAudioInputCatalog.Input> inputs = PhoneAudioInputCatalog.list(context);
        List<String> labels = new ArrayList<>();
        String saved = AppSettings.getPhoneAudioInput(context);
        int checked = 0;
        for (int index = 0; index < inputs.size(); index++) {
            labels.add(inputs.get(index).label);
            if (inputs.get(index).key.equals(saved)) checked = index;
        }
        spinner.setVisibility(View.VISIBLE);
        spinner.setAdapter(adapter(labels));
        spinner.setSelection(checked);
        valueOne.setVisibility(View.VISIBLE);
        valueOne.setText("选择后录制、传输和电平表同步切换");
        spinner.setOnItemSelectedListener(selection(position -> {
            AppSettings.setPhoneAudioInput(context, inputs.get(position).key);
            PhoneAudioHub.reset();
            host.microphoneChanged();
        }));
        finishPrepare();
    }

    private void showFrameMode() {
        prepare("画面比例");
        PhoneCameraControls.State state = source.controls();
        spinner.setVisibility(View.VISIBLE);
        spinner.setAdapter(adapter(Arrays.asList(
                "等比铺满（不拉伸）", "完整画面（可能有黑边）")));
        spinner.setSelection(state.scaleMode == PhoneCameraControls.SCALE_FIT ? 1 : 0);
        valueOne.setVisibility(View.VISIBLE);
        valueOne.setText("铺满会等比裁切边缘，完整画面会保留传感器全部内容");
        spinner.setOnItemSelectedListener(selection(position -> {
            PhoneCameraControls.State next = source.controls();
            next.scaleMode = position == 1
                    ? PhoneCameraControls.SCALE_FIT : PhoneCameraControls.SCALE_FILL;
            apply(next);
        }));
        finishPrepare();
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, R.layout.spinner_item, values);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        return adapter;
    }

    private AdapterView.OnItemSelectedListener selection(PositionListener listener) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (!updating) listener.changed(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
    }

    private SeekBar.OnSeekBarChangeListener seek(ProgressListener listener) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!updating && fromUser) listener.changed(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private static String awbLabel(int mode) {
        switch (mode) {
            case CaptureRequest.CONTROL_AWB_MODE_AUTO:
                return "自动";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT:
                return "白炽灯";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT:
                return "荧光灯";
            case CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT:
                return "暖色荧光灯";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT:
                return "日光";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                return "阴天";
            case CaptureRequest.CONTROL_AWB_MODE_TWILIGHT:
                return "黄昏";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE:
                return "阴影";
            default:
                return "白平衡 " + mode;
        }
    }

    private static String afLabel(int mode) {
        switch (mode) {
            case CaptureRequest.CONTROL_AF_MODE_OFF:
                return "手动对焦";
            case CaptureRequest.CONTROL_AF_MODE_AUTO:
                return "单次自动";
            case CaptureRequest.CONTROL_AF_MODE_MACRO:
                return "微距";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                return "连续视频";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                return "连续拍照";
            case CaptureRequest.CONTROL_AF_MODE_EDOF:
                return "扩展景深";
            default:
                return "对焦 " + mode;
        }
    }

    private static String shutterLabel(long nanoseconds) {
        if (nanoseconds <= 0) return "--";
        double seconds = nanoseconds / 1_000_000_000d;
        return seconds < 0.75d
                ? "1/" + Math.max(1, Math.round(1d / seconds)) + " 秒"
                : String.format(Locale.CHINA, "%.2f 秒", seconds);
    }

    private interface PositionListener {
        void changed(int position);
    }

    private interface ProgressListener {
        void changed(int progress);
    }
}
