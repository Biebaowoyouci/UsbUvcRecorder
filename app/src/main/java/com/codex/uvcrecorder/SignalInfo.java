package com.codex.uvcrecorder;

import com.serenegiant.usb.Format;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;

import java.util.List;
import java.util.Locale;

public final class SignalInfo {
    public final int width;
    public final int height;
    public final int fps;
    public final int frameType;
    public final String formatName;
    public final String frameRateText;
    public final boolean interlaced;
    public final String scanType;

    private SignalInfo(int width, int height, int fps, int frameType,
                       String formatName, String frameRateText, boolean interlaced) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.frameType = frameType;
        this.formatName = formatName;
        this.frameRateText = frameRateText;
        this.interlaced = interlaced;
        this.scanType = interlaced ? "i" : "p";
    }

    public static SignalInfo from(Size size, List<Format> formats) {
        double exactRate = size.fps;
        int interlaceFlags = 0;
        if (formats != null) {
            outer:
            for (Format format : formats) {
                if (format.frameDescriptors == null) continue;
                for (Format.Descriptor descriptor : format.frameDescriptors) {
                    if (descriptor.type != size.type || descriptor.width != size.width
                            || descriptor.height != size.height || descriptor.intervals == null) {
                        continue;
                    }
                    interlaceFlags = format.interlaceFlags;
                    for (Format.Interval interval : descriptor.intervals) {
                        if (interval.fps == size.fps && interval.value > 0) {
                            exactRate = 10_000_000.0 / interval.value;
                            break outer;
                        }
                    }
                }
            }
        }
        boolean interlaced = (interlaceFlags & 0x01) != 0;
        // UVC intervals are complete-frame rates. Broadcast interlaced notation uses the
        // field rate (25 frames/s is displayed as 50i, 29.97 frames/s as 59.94i).
        double signalRate = interlaced && exactRate <= 30.5 ? exactRate * 2.0 : exactRate;
        String rate = formatRate(signalRate);
        String format = size.type == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG"
                : size.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED ? "YUY2"
                : "UVC(type " + size.type + ")";
        return new SignalInfo(size.width, size.height, Math.max(1, size.fps), size.type,
                format, rate, interlaced);
    }

    public static SignalInfo network(int width, int height, int fps, String formatName) {
        String format = formatName == null || formatName.trim().isEmpty()
                ? "网络流" : formatName.trim();
        int safeFps = Math.max(1, fps);
        return new SignalInfo(width, height, safeFps, -1, format,
                formatRate(safeFps), false);
    }

    public String displayText() {
        return width + " × " + height + "   " + frameRateText + scanType + "   " + formatName;
    }

    private static String formatRate(double rate) {
        if (Math.abs(rate - Math.rint(rate)) < 0.01) {
            return String.format(Locale.US, "%.0f", rate);
        }
        return String.format(Locale.US, "%.2f", rate);
    }
}
