package com.codex.uvcrecorder;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One independently selectable video input exposed by either USB host or Camera2. */
final class VideoInputDevice {
    enum Kind { USB, PHONE_CAMERA }

    final Kind kind;
    final UsbDevice usbDevice;
    final String cameraId;
    final String logicalCameraId;
    final String physicalCameraId;
    final String label;
    final String stableKey;

    private VideoInputDevice(Kind kind, UsbDevice usbDevice, String cameraId,
                             String logicalCameraId, String physicalCameraId,
                             String label, String stableKey) {
        this.kind = kind;
        this.usbDevice = usbDevice;
        this.cameraId = cameraId;
        this.logicalCameraId = logicalCameraId;
        this.physicalCameraId = physicalCameraId;
        this.label = label;
        this.stableKey = stableKey;
    }

    static VideoInputDevice usb(UsbDevice device) {
        return new VideoInputDevice(Kind.USB, device, null, null, null,
                UsbDeviceCatalog.label(device),
                UsbDeviceCatalog.stableKey(device));
    }

    static VideoInputDevice phone(String cameraId, String label) {
        return phone(cameraId, null, label);
    }

    static VideoInputDevice phone(String logicalCameraId, String physicalCameraId,
                                  String label) {
        String selectedId = physicalCameraId == null ? logicalCameraId : physicalCameraId;
        String key = "phone-camera:" + logicalCameraId
                + (physicalCameraId == null ? "" : ":physical:" + physicalCameraId);
        return new VideoInputDevice(Kind.PHONE_CAMERA, null, selectedId,
                logicalCameraId, physicalCameraId, label, key);
    }

    static List<VideoInputDevice> listAll(Context context) {
        List<VideoInputDevice> result = new ArrayList<>();
        for (UsbDevice device : UsbDeviceCatalog.listVideoInputs(context)) {
            result.add(usb(device));
        }
        result.addAll(PhoneCameraCatalog.list(context));
        return result;
    }

    boolean isUsb() {
        return kind == Kind.USB;
    }

    boolean isPhoneCamera() {
        return kind == Kind.PHONE_CAMERA;
    }

    String fileTag(int index) {
        if (isUsb()) return UsbDeviceCatalog.fileTag(usbDevice, index);
        String normalized = label.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5]+", "_");
        if (normalized.length() > 36) normalized = normalized.substring(0, 36);
        return String.format(Locale.US, "D%02d_PHONE_%s", index + 1, normalized);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VideoInputDevice
                && stableKey.equals(((VideoInputDevice) other).stableKey);
    }

    @Override
    public int hashCode() {
        return stableKey.hashCode();
    }
}
