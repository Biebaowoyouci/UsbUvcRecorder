package com.codex.uvcrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;

/** Persisted Camera2 controls. Values are clamped against each real camera. */
final class PhoneCameraControls {
    static final int SCALE_FILL = 0;
    static final int SCALE_FIT = 1;

    private static final String PREFS = "phone_camera_controls";

    private PhoneCameraControls() {
    }

    static final class State {
        int awbMode;
        int exposureCompensation;
        float zoomRatio;
        int afMode;
        float focusDistance;
        int beauty;
        boolean autoExposure;
        int iso;
        long exposureTimeNs;
        int scaleMode;

        State copy() {
            State copy = new State();
            copy.awbMode = awbMode;
            copy.exposureCompensation = exposureCompensation;
            copy.zoomRatio = zoomRatio;
            copy.afMode = afMode;
            copy.focusDistance = focusDistance;
            copy.beauty = beauty;
            copy.autoExposure = autoExposure;
            copy.iso = iso;
            copy.exposureTimeNs = exposureTimeNs;
            copy.scaleMode = scaleMode;
            return copy;
        }
    }

    static State load(Context context, PhoneCameraCatalog.Device device) {
        State defaults = defaults(device);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefix = key(device.id);
        State state = new State();
        state.awbMode = preferences.getInt(prefix + "awb", defaults.awbMode);
        state.exposureCompensation = preferences.getInt(prefix + "brightness",
                defaults.exposureCompensation);
        state.zoomRatio = preferences.getFloat(prefix + "zoom", defaults.zoomRatio);
        state.afMode = preferences.getInt(prefix + "af", defaults.afMode);
        state.focusDistance = preferences.getFloat(prefix + "focus",
                defaults.focusDistance);
        state.beauty = preferences.getInt(prefix + "beauty", defaults.beauty);
        state.autoExposure = preferences.getBoolean(prefix + "auto_exposure", true);
        state.iso = preferences.getInt(prefix + "iso", defaults.iso);
        state.exposureTimeNs = preferences.getLong(prefix + "shutter",
                defaults.exposureTimeNs);
        state.scaleMode = preferences.getInt(prefix + "scale", SCALE_FILL);
        return clamp(device, state);
    }

    static void save(Context context, PhoneCameraCatalog.Device device, State value) {
        State state = clamp(device, value);
        String prefix = key(device.id);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(prefix + "awb", state.awbMode)
                .putInt(prefix + "brightness", state.exposureCompensation)
                .putFloat(prefix + "zoom", state.zoomRatio)
                .putInt(prefix + "af", state.afMode)
                .putFloat(prefix + "focus", state.focusDistance)
                .putInt(prefix + "beauty", state.beauty)
                .putBoolean(prefix + "auto_exposure", state.autoExposure)
                .putInt(prefix + "iso", state.iso)
                .putLong(prefix + "shutter", state.exposureTimeNs)
                .putInt(prefix + "scale", state.scaleMode)
                .apply();
    }

    static State defaults(PhoneCameraCatalog.Device device) {
        PhoneCameraCatalog.Capabilities capabilities = device.capabilities;
        State state = new State();
        state.awbMode = choose(capabilities.awbModes,
                CaptureRequest.CONTROL_AWB_MODE_AUTO);
        state.exposureCompensation = 0;
        state.zoomRatio = clamp(1f, capabilities.minimumZoom(),
                capabilities.maximumZoom());
        if (capabilities.hasAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            state.afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        } else if (capabilities.hasAfMode(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            state.afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        } else {
            state.afMode = choose(capabilities.afModes,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
        }
        state.focusDistance = 0f;
        state.beauty = 0;
        state.autoExposure = true;
        Range<Integer> iso = capabilities.sensitivityRange;
        state.iso = iso == null ? 100 : clamp(100, iso.getLower(), iso.getUpper());
        Range<Long> exposure = capabilities.exposureTimeRange;
        state.exposureTimeNs = exposure == null ? 10_000_000L
                : clamp(10_000_000L, exposure.getLower(), exposure.getUpper());
        state.scaleMode = SCALE_FILL;
        return state;
    }

    static State clamp(PhoneCameraCatalog.Device device, State value) {
        State defaults = defaults(device);
        if (value == null) return defaults;
        PhoneCameraCatalog.Capabilities capabilities = device.capabilities;
        State result = value.copy();
        if (!contains(capabilities.awbModes, result.awbMode)) {
            result.awbMode = defaults.awbMode;
        }
        Range<Integer> compensation = capabilities.exposureCompensationRange;
        result.exposureCompensation = compensation == null ? 0
                : clamp(result.exposureCompensation, compensation.getLower(),
                compensation.getUpper());
        result.zoomRatio = clamp(result.zoomRatio, capabilities.minimumZoom(),
                capabilities.maximumZoom());
        if (!contains(capabilities.afModes, result.afMode)) {
            result.afMode = defaults.afMode;
        }
        result.focusDistance = clamp(result.focusDistance, 0f,
                capabilities.minimumFocusDistance);
        result.beauty = clamp(result.beauty, 0, 100);
        if (!capabilities.supportsManualExposure()) result.autoExposure = true;
        Range<Integer> iso = capabilities.sensitivityRange;
        result.iso = iso == null ? defaults.iso
                : clamp(result.iso, iso.getLower(), iso.getUpper());
        Range<Long> exposure = capabilities.exposureTimeRange;
        result.exposureTimeNs = exposure == null ? defaults.exposureTimeNs
                : clamp(result.exposureTimeNs, exposure.getLower(), exposure.getUpper());
        if (result.scaleMode != SCALE_FIT) result.scaleMode = SCALE_FILL;
        return result;
    }

    static int longToProgress(long value, long minimum, long maximum) {
        if (minimum <= 0 || maximum <= minimum) return 0;
        double fraction = Math.log(clamp(value, minimum, maximum) / (double) minimum)
                / Math.log(maximum / (double) minimum);
        return clamp((int) Math.round(fraction * 100d), 0, 100);
    }

    static long progressToLong(int progress, long minimum, long maximum) {
        if (minimum <= 0 || maximum <= minimum) return minimum;
        double fraction = clamp(progress, 0, 100) / 100d;
        return clamp(Math.round(minimum * Math.pow(maximum / (double) minimum, fraction)),
                minimum, maximum);
    }

    private static int choose(int[] values, int preferred) {
        if (contains(values, preferred)) return preferred;
        return values == null || values.length == 0 ? 0 : values[0];
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static String key(String cameraId) {
        return "camera_" + cameraId.replaceAll("[^A-Za-z0-9_-]", "_") + "_";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
