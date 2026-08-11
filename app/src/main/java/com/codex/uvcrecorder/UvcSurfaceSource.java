package com.codex.uvcrecorder;

import android.content.Context;
import android.view.Surface;

/** A live UVC source that can mirror frames into one or more encoder surfaces. */
interface UvcSurfaceSource {
    void addRecordingSurface(Surface surface) throws Exception;

    void removeRecordingSurface(Surface surface);

    default void addDisplaySurface(Surface surface, int width, int height) throws Exception {
        throw new UnsupportedOperationException("当前信号源不支持外接显示器输出");
    }

    default void removeDisplaySurface(Surface surface) {
    }

    default void setOutputRotation(int degrees) {
    }

    default int getOutputRotation() {
        return 0;
    }

    default int getRecordingWidth(int inputWidth, int inputHeight) {
        int rotation = getOutputRotation();
        return rotation == 90 || rotation == 270 ? inputHeight : inputWidth;
    }

    default int getRecordingHeight(int inputWidth, int inputHeight) {
        int rotation = getOutputRotation();
        return rotation == 90 || rotation == 270 ? inputWidth : inputHeight;
    }

    default PcmAudioSubscription subscribeAudio(Context context) throws Exception {
        return UsbAudioHub.subscribe(context);
    }

    default boolean requiresRecordAudioPermission() {
        return true;
    }
}
