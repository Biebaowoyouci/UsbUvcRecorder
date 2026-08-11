package com.codex.uvcrecorder;

import android.content.Context;

import com.serenegiant.utils.UVCUtils;

final class UsbRecorderApplicationHolder {
    private UsbRecorderApplicationHolder() {
    }

    static Context context() {
        return UVCUtils.getApplication();
    }
}
