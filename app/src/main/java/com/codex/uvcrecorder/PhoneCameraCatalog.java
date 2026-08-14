package com.codex.uvcrecorder;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Enumerates Camera2 devices using the same YUV stream path as the reference APK. */
final class PhoneCameraCatalog {
    private static final int[][] VISIBLE_SIZES = {
            {3840, 2160},
            {1920, 1080},
            {1280, 720}
    };
    private static final int[] VISIBLE_FRAME_RATES = {25, 30, 60};

    private PhoneCameraCatalog() {
    }

    static final class Device {
        final String id;
        final String openId;
        final String physicalId;
        final String label;
        final int lensFacing;
        final int sensorOrientation;
        final List<Mode> modes;
        final Capabilities capabilities;

        Device(String id, String openId, String physicalId, String label,
               int lensFacing, int sensorOrientation, List<Mode> modes,
               Capabilities capabilities) {
            this.id = id;
            this.openId = openId;
            this.physicalId = physicalId;
            this.label = label;
            this.lensFacing = lensFacing;
            this.sensorOrientation = sensorOrientation;
            this.modes = modes;
            this.capabilities = capabilities;
        }
    }

    static final class Capabilities {
        final int[] awbModes;
        final int[] afModes;
        final int[] aeModes;
        final int[] noiseReductionModes;
        final int[] edgeModes;
        final Range<Integer> exposureCompensationRange;
        final Rational exposureCompensationStep;
        final Range<Integer> sensitivityRange;
        final Range<Long> exposureTimeRange;
        final Range<Float> zoomRatioRange;
        final float maxDigitalZoom;
        final float minimumFocusDistance;
        final Rect activeArray;

        Capabilities(CameraCharacteristics characteristics) {
            awbModes = values(characteristics.get(
                    CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES));
            afModes = values(characteristics.get(
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
            aeModes = values(characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES));
            noiseReductionModes = values(characteristics.get(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES));
            edgeModes = values(characteristics.get(
                    CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES));
            exposureCompensationRange = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            exposureCompensationStep = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            sensitivityRange = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposureTimeRange = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Float> zoomRange = null;
            if (Build.VERSION.SDK_INT >= 30) {
                zoomRange = characteristics.get(
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            }
            zoomRatioRange = zoomRange;
            Float maximum = characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxDigitalZoom = maximum == null ? 1f : Math.max(1f, maximum);
            Float focus = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            minimumFocusDistance = focus == null ? 0f : Math.max(0f, focus);
            activeArray = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        }

        boolean hasAwbMode(int mode) {
            return contains(awbModes, mode);
        }

        boolean hasAfMode(int mode) {
            return contains(afModes, mode);
        }

        boolean supportsManualExposure() {
            return sensitivityRange != null && exposureTimeRange != null
                    && contains(aeModes, 0);
        }

        float minimumZoom() {
            return zoomRatioRange == null ? 1f : zoomRatioRange.getLower();
        }

        float maximumZoom() {
            return zoomRatioRange == null ? maxDigitalZoom : zoomRatioRange.getUpper();
        }
    }

    static final class Mode {
        final int width;
        final int height;
        final int fps;
        final Range<Integer> aeRange;

        Mode(int width, int height, int fps, Range<Integer> aeRange) {
            this.width = width;
            this.height = height;
            this.fps = Math.max(1, fps);
            this.aeRange = aeRange;
        }

        String label() {
            String tier = width == 3840 && height == 2160
                    ? "4K" : height + "P";
            return tier + "  " + width + " × " + height + "  " + fps + "p";
        }

        boolean matches(int[] saved) {
            return saved != null && saved.length == 3
                    && width == saved[0] && height == saved[1] && fps == saved[2];
        }
    }

    static List<Device> list(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return Collections.emptyList();
        List<Device> result = new ArrayList<>();
        Set<String> topLevelIds = new LinkedHashSet<>();
        try {
            Collections.addAll(topLevelIds, manager.getCameraIdList());
        } catch (Exception ignored) {
            return result;
        }
        Set<String> added = new LinkedHashSet<>();
        for (String id : topLevelIds) {
            try {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Device logical = createDevice(id, id, "", characteristics,
                        facingLabel(facing(characteristics)) + "（ID " + id + "）");
                if (logical != null && added.add(logical.id)) result.add(logical);

                if (Build.VERSION.SDK_INT >= 28) {
                    for (String physicalId : characteristics.getPhysicalCameraIds()) {
                        if (topLevelIds.contains(physicalId)) continue;
                        try {
                            CameraCharacteristics physical =
                                    manager.getCameraCharacteristics(physicalId);
                            String key = id + "#" + physicalId;
                            Device lens = createDevice(key, id, physicalId, physical,
                                    facingLabel(facing(physical)) + "实体镜头（"
                                            + physicalId + " / 逻辑 " + id + "）");
                            if (lens != null && added.add(lens.id)) result.add(lens);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        result.sort(Comparator.comparingInt(device -> facingRank(device.lensFacing)));
        return result;
    }

    static Device find(List<Device> devices, String id) {
        if (devices == null || devices.isEmpty()) return null;
        if (id != null) {
            for (Device device : devices) {
                if (device.id.equals(id)) return device;
            }
        }
        for (Device device : devices) {
            if (device.lensFacing == CameraCharacteristics.LENS_FACING_BACK) return device;
        }
        return devices.get(0);
    }

    static Mode chooseMode(Device device, int[] saved) {
        if (device == null || device.modes.isEmpty()) return null;
        if (isVisibleSelection(saved)) {
            for (Mode mode : device.modes) {
                if (mode.matches(saved)) return mode;
            }
            Mode closest = null;
            int closestScore = Integer.MAX_VALUE;
            for (Mode mode : device.modes) {
                int score = compatibilityScore(mode, saved);
                if (closest == null || score < closestScore
                        || (score == closestScore && compare(mode, closest) > 0)) {
                    closest = mode;
                    closestScore = score;
                }
            }
            if (closest != null) return closest;
        }
        // Match the phone camera and reference broadcast app: use the
        // camera-reported preferred video preview rather than the largest
        // still-photo SurfaceTexture size. The latter was selecting
        // 4096x3072 on this handset and then cropping it as a portrait stream.
        for (Mode mode : device.modes) {
            if (mode.width == 1920 && mode.height == 1080 && mode.fps == 30) {
                return mode;
            }
        }
        Mode best = null;
        for (Mode mode : device.modes) {
            if (mode.width > 4096 || mode.height > 4096 || mode.fps > 60) continue;
            float aspect = mode.width / (float) mode.height;
            if (Math.abs(aspect - 16f / 9f) > 0.04f) continue;
            if (best == null || compare(mode, best) > 0) best = mode;
        }
        return best == null ? device.modes.get(0) : best;
    }

    private static List<Mode> buildModes(StreamConfigurationMap map,
                                         Range<Integer>[] ranges) {
        if (map == null) return Collections.emptyList();
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null) return Collections.emptyList();
        List<Range<Integer>> availableRanges = new ArrayList<>();
        if (ranges != null) Collections.addAll(availableRanges, ranges);
        if (availableRanges.isEmpty()) availableRanges.add(new Range<>(15, 30));
        // Camera apps use a fixed target range when one is advertised. This
        // prevents the same "30p" item from silently using a variable 10-30
        // range while the reference app requests [30, 30].
        availableRanges.sort((left, right) -> {
            boolean leftFixed = left.getLower().equals(left.getUpper());
            boolean rightFixed = right.getLower().equals(right.getUpper());
            if (leftFixed != rightFixed) return leftFixed ? -1 : 1;
            return Integer.compare(right.getUpper(), left.getUpper());
        });

        List<Mode> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Size size : sizes) {
            if (!isVisibleResolution(size.getWidth(), size.getHeight())) continue;
            long durationNs = 0;
            try {
                durationNs = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size);
            } catch (Exception ignored) {
            }
            int maximum = durationNs > 0
                    ? Math.max(1, (int) Math.round(1_000_000_000d / durationNs)) : 60;
            maximum = Math.min(60, maximum);
            for (int fps : VISIBLE_FRAME_RATES) {
                if (fps > maximum + 1) continue;
                Range<Integer> range = bestRangeForTarget(availableRanges, fps);
                if (range == null) continue;
                String key = size.getWidth() + "x" + size.getHeight() + "@" + fps;
                if (seen.add(key)) {
                    result.add(new Mode(size.getWidth(), size.getHeight(), fps, range));
                }
            }
        }
        result.sort((left, right) -> -compare(left, right));
        return result;
    }

    static boolean isVisibleResolution(int width, int height) {
        for (int[] size : VISIBLE_SIZES) {
            if (width == size[0] && height == size[1]) return true;
        }
        return false;
    }

    static boolean isVisibleFrameRate(int fps) {
        for (int value : VISIBLE_FRAME_RATES) {
            if (fps == value) return true;
        }
        return false;
    }

    static boolean isVisibleSelection(int[] mode) {
        return mode != null && mode.length == 3
                && isVisibleResolution(mode[0], mode[1])
                && isVisibleFrameRate(mode[2]);
    }

    private static Range<Integer> bestRangeForTarget(List<Range<Integer>> ranges,
                                                     int target) {
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range.getLower() > target || range.getUpper() < target) continue;
            int span = range.getUpper() - range.getLower();
            int score;
            if (range.getLower() == target && range.getUpper() == target) {
                score = 0;
            } else if (range.getUpper() == target) {
                score = 100 + span;
            } else {
                score = 200 + span + Math.abs(range.getUpper() - target);
            }
            if (best == null || score < bestScore) {
                best = range;
                bestScore = score;
            }
        }
        return best;
    }

    private static int compatibilityScore(Mode mode, int[] saved) {
        int resolutionDistance = Math.abs(resolutionRank(mode.width, mode.height)
                - resolutionRank(saved[0], saved[1]));
        int frameRateDistance = Math.abs(mode.fps - saved[2]);
        // Keep the selected resolution tier first. If that exact rate is not
        // exposed by another lens, its nearest 25/30/60 rate is the fallback.
        return resolutionDistance * 1_000 + frameRateDistance * 10;
    }

    private static int resolutionRank(int width, int height) {
        for (int i = 0; i < VISIBLE_SIZES.length; i++) {
            if (width == VISIBLE_SIZES[i][0] && height == VISIBLE_SIZES[i][1]) {
                return VISIBLE_SIZES.length - i;
            }
        }
        return 0;
    }

    private static Device createDevice(String key, String openId, String physicalId,
                                       CameraCharacteristics characteristics, String label) {
        Integer orientationValue =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int orientation = orientationValue == null ? 0 : orientationValue;
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        List<Mode> modes = buildModes(map, ranges);
        if (modes.isEmpty()) return null;
        return new Device(key, openId, physicalId, label, facing(characteristics),
                orientation, modes, new Capabilities(characteristics));
    }

    private static int facing(CameraCharacteristics characteristics) {
        Integer value = characteristics.get(CameraCharacteristics.LENS_FACING);
        return value == null ? CameraCharacteristics.LENS_FACING_EXTERNAL : value;
    }

    private static int compare(Mode left, Mode right) {
        long leftPixels = (long) left.width * left.height;
        long rightPixels = (long) right.width * right.height;
        if (leftPixels != rightPixels) return Long.compare(leftPixels, rightPixels);
        return Integer.compare(left.fps, right.fps);
    }

    private static int facingRank(int facing) {
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return 0;
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return 1;
        return 2;
    }

    private static String facingLabel(int facing) {
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "后置摄像头";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "前置摄像头";
        return "外接/其他摄像头";
    }

    private static int[] values(int[] values) {
        return values == null ? new int[0] : values.clone();
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }
}
