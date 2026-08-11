package com.codex.uvcrecorder;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.view.Surface;
import android.view.WindowManager;

import com.serenegiant.usb.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enumerates Camera2 cameras that Android exposes as independently openable devices. */
final class PhoneCameraCatalog {
    static final int FRAME_TYPE_CAMERA2 = 0x43414D32;

    private PhoneCameraCatalog() {
    }

    static List<VideoInputDevice> list(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return Collections.emptyList();
        List<VideoInputDevice> result = new ArrayList<>();
        Map<Integer, Integer> facingCounts = new HashMap<>();
        try {
            String[] cameraIds = manager.getCameraIdList();
            // Some vendors return physical IDs in getCameraIdList() as well as under their
            // logical parent.  Remember those children so a lens is never shown twice.
            Set<String> physicalChildren = new HashSet<>();
            for (String cameraId : cameraIds) {
                try {
                    physicalChildren.addAll(manager.getCameraCharacteristics(cameraId)
                            .getPhysicalCameraIds());
                } catch (Throwable ignored) {
                }
            }
            Set<String> emittedPhysicalIds = new HashSet<>();
            for (String logicalId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(logicalId);
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null || map.getOutputSizes(SurfaceTexture.class) == null) continue;
                Integer facingValue = characteristics.get(CameraCharacteristics.LENS_FACING);
                int facing = facingValue == null ? -1 : facingValue;
                String side;
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) side = "前置";
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) side = "后置";
                else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) side = "外接";
                else side = "其他";
                Set<String> physicalIds = characteristics.getPhysicalCameraIds();
                if (physicalIds != null && !physicalIds.isEmpty()) {
                    List<String> sortedIds = new ArrayList<>(physicalIds);
                    Collections.sort(sortedIds);
                    for (String physicalId : sortedIds) {
                        if (!emittedPhysicalIds.add(physicalId)) continue;
                        int number = facingCounts.getOrDefault(facing, 0) + 1;
                        facingCounts.put(facing, number);
                        String focal = focalLengthLabel(manager, physicalId);
                        String label = "手机" + side + "摄像头 " + number + focal
                                + "  [Physical " + physicalId + " / Logical " + logicalId + "]";
                        result.add(VideoInputDevice.phone(logicalId, physicalId, label));
                    }
                } else {
                    if (physicalChildren.contains(logicalId)) continue;
                    int number = facingCounts.getOrDefault(facing, 0) + 1;
                    facingCounts.put(facing, number);
                    String label = "手机" + side + "摄像头 " + number
                            + "  [Camera " + logicalId + "]";
                    result.add(VideoInputDevice.phone(logicalId, null, label));
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    static List<Size> modes(Context context, String cameraId) throws Exception {
        return modes(context, cameraId, null);
    }

    static List<Size> modes(Context context, String logicalCameraId,
                            String physicalCameraId) throws Exception {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return Collections.emptyList();
        CameraCharacteristics characteristics;
        try {
            characteristics = manager.getCameraCharacteristics(
                    physicalCameraId == null ? logicalCameraId : physicalCameraId);
        } catch (Throwable ignored) {
            characteristics = manager.getCameraCharacteristics(logicalCameraId);
        }
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return Collections.emptyList();
        android.util.Size[] outputs = map.getOutputSizes(SurfaceTexture.class);
        if (outputs == null) return Collections.emptyList();
        Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        List<Size> result = new ArrayList<>();
        for (android.util.Size output : outputs) {
            int width = output.getWidth();
            int height = output.getHeight();
            if (!isProductionResolution(width, height)) continue;
            long minimumDuration = map.getOutputMinFrameDuration(SurfaceTexture.class, output);
            int maximumFps = minimumDuration > 0
                    ? Math.max(1, (int) Math.floor(1_000_000_000.0 / minimumDuration)) : 30;
            List<Integer> supported = new ArrayList<>();
            for (int fps : new int[]{60, 50, 30, 25, 24}) {
                if (fps <= maximumFps && contains(ranges, fps)) supported.add(fps);
            }
            if (supported.isEmpty()) supported.add(Math.min(30, maximumFps));
            for (int fps : supported) {
                result.add(new Size(FRAME_TYPE_CAMERA2, width, height, fps,
                        new ArrayList<>(Collections.singletonList(fps))));
            }
        }
        result.sort((left, right) -> {
            long lp = (long) left.width * left.height;
            long rp = (long) right.width * right.height;
            if (lp != rp) return -Long.compare(lp, rp);
            return -Integer.compare(left.fps, right.fps);
        });
        return result;
    }

    static int sensorOrientation(Context context, VideoInputDevice input) {
        if (input == null || !input.isPhoneCamera()) return 0;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return 0;
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(
                    input.physicalCameraId == null
                            ? input.logicalCameraId : input.physicalCameraId);
            Integer value = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return value == null ? 0 : value;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static boolean isFrontFacing(Context context, VideoInputDevice input) {
        if (input == null || !input.isPhoneCamera()) return false;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return false;
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(
                    input.physicalCameraId == null
                            ? input.logicalCameraId : input.physicalCameraId);
            Integer value = characteristics.get(CameraCharacteristics.LENS_FACING);
            return value != null && value == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int relativeRotation(Context context, VideoInputDevice input) {
        WindowManager windowManager = (WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE);
        int displayRotation = windowManager == null ? Surface.ROTATION_0
                : windowManager.getDefaultDisplay().getRotation();
        int displayDegrees;
        if (displayRotation == Surface.ROTATION_90) displayDegrees = 90;
        else if (displayRotation == Surface.ROTATION_180) displayDegrees = 180;
        else if (displayRotation == Surface.ROTATION_270) displayDegrees = 270;
        else displayDegrees = 0;
        /*
         * Camera2's SurfaceTexture already carries the camera-buffer transform.  Adding
         * SENSOR_ORIENTATION here rotates the preview a second time (most phone sensors
         * report 90 degrees), which also makes a portrait frame get laid out as landscape.
         * The view only needs to cancel the display rotation; front-facing mirroring is
         * applied separately by the caller.
         */
        return (360 - displayDegrees) % 360;
    }

    private static String focalLengthLabel(CameraManager manager, String cameraId) {
        try {
            float[] lengths = manager.getCameraCharacteristics(cameraId).get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (lengths != null && lengths.length > 0) {
                return String.format(java.util.Locale.US, " · %.1fmm", lengths[0]);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isProductionResolution(int width, int height) {
        return (height == 2160 && (width == 3840 || width == 4096))
                || (width == 1920 && height == 1080)
                || (width == 1280 && height == 720);
    }

    private static boolean contains(Range<Integer>[] ranges, int fps) {
        if (ranges == null || ranges.length == 0) return fps <= 30;
        return Arrays.stream(ranges).anyMatch(range -> range.contains(fps));
    }
}
