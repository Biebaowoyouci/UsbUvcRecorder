package com.codex.uvcrecorder;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class UsbDeviceCatalog {
    private UsbDeviceCatalog() {
    }

    static List<UsbDevice> listVideoInputs(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> result = new ArrayList<>();
        if (manager == null) return result;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (hasVideoStreamingEndpoint(device, UsbConstants.USB_DIR_IN)) result.add(device);
        }
        result.sort(Comparator.comparing(UsbDeviceCatalog::label,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static List<UsbDevice> listVideoOutputs(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> result = new ArrayList<>();
        if (manager == null) return result;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (hasVideoStreamingEndpoint(device, UsbConstants.USB_DIR_OUT)) result.add(device);
        }
        result.sort(Comparator.comparing(UsbDeviceCatalog::label,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static boolean isVideoInput(UsbDevice device) {
        return hasVideoStreamingEndpoint(device, UsbConstants.USB_DIR_IN);
    }

    static boolean isVideoOutput(UsbDevice device) {
        return hasVideoStreamingEndpoint(device, UsbConstants.USB_DIR_OUT);
    }

    private static boolean hasVideoStreamingEndpoint(UsbDevice device, int direction) {
        if (device == null) return false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() != UsbConstants.USB_CLASS_VIDEO) continue;
            // UVC VideoStreaming is subclass 2. Some inexpensive devices incorrectly use
            // subclass 0, so accept any video interface that actually exposes a data pipe.
            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                if (endpoint.getDirection() == direction
                        && (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        || endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC)) {
                    return true;
                }
            }
        }
        // A control-only video interface is not a usable input/output stream.
        return false;
    }

    static String label(UsbDevice device) {
        if (device == null) return "UVC";
        String product = device.getProductName();
        if (product == null || product.trim().isEmpty()) product = "UVC 设备";
        return product + String.format(Locale.US, "  [%04X:%04X]",
                device.getVendorId(), device.getProductId())
                + (isVideoOutput(device) ? "  · UVC OUT" : "");
    }

    static String fileTag(UsbDevice device, int index) {
        String product = device == null ? "UVC" : device.getProductName();
        if (product == null || product.trim().isEmpty()) product = "UVC";
        String normalized = product.replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) normalized = "UVC";
        if (normalized.length() > 24) normalized = normalized.substring(0, 24);
        return String.format(Locale.US, "D%02d_%s", index + 1, normalized);
    }

    static String stableKey(UsbDevice device) {
        if (device == null) return "";
        String product = device.getProductName();
        if (product == null) product = "";
        return device.getVendorId() + ":" + device.getProductId() + ":"
                + product.replace('\n', ' ').replace('\r', ' ');
    }
}
