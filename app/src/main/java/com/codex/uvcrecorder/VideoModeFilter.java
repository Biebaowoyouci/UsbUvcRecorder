package com.codex.uvcrecorder;

import com.serenegiant.usb.Size;

/** Keeps production capture resolutions while preserving every advertised P/i frame rate. */
final class VideoModeFilter {
    private VideoModeFilter() {
    }

    static boolean keep(Size size) {
        if (size == null) return false;
        return (size.height == 2160 && (size.width == 3840 || size.width == 4096))
                || (size.width == 1920 && size.height == 1080)
                || (size.width == 1280 && size.height == 720);
    }
}
