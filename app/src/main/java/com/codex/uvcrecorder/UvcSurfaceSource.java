package com.codex.uvcrecorder;

import android.content.Context;
import android.view.Surface;

/** A live UVC source that can mirror frames into one or more encoder surfaces. */
interface UvcSurfaceSource {
    void addRecordingSurface(Surface surface) throws Exception;

    default void addRecordingSurface(Surface surface, int width, int height) throws Exception {
        addRecordingSurface(surface);
    }

    void removeRecordingSurface(Surface surface);

    default void addDisplaySurface(Surface surface, int width, int height) throws Exception {
        throw new UnsupportedOperationException("当前信号源不支持外接显示器输出");
    }

    default void removeDisplaySurface(Surface surface) {
    }

    default void setScreenSurface(Surface surface, int width, int height) {
    }

    default void setOutputRotation(int degrees) {
    }

    default int getOutputRotation() {
        return 0;
    }

    /**
     * Latest source-video timestamp in microseconds, or {@link Long#MIN_VALUE}
     * when the source uses the device monotonic clock implicitly.
     */
    default long getVideoTimelinePositionUs() {
        return Long.MIN_VALUE;
    }

    /**
     * Increments when a live source is rebuilt and its media timestamps can
     * restart from a different origin. Recorder tracks use this to re-anchor
     * both audio and video onto one continuous file timeline.
     */
    default int getTimelineGeneration() {
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
