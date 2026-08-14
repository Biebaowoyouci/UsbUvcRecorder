package com.codex.uvcrecorder;

import android.content.Context;
import android.view.Surface;

/** Delegates video while allowing audio only from the UAC matched to that video device. */
final class StrictAudioSurfaceSource implements UvcSurfaceSource {
    private final UvcSurfaceSource videoSource;
    private final String preferredAudioProduct;
    private final int preferredAudioDeviceId;

    StrictAudioSurfaceSource(UvcSurfaceSource videoSource, String preferredAudioProduct) {
        this(videoSource, preferredAudioProduct, -1);
    }

    StrictAudioSurfaceSource(UvcSurfaceSource videoSource, String preferredAudioProduct,
                             int preferredAudioDeviceId) {
        this.videoSource = videoSource;
        this.preferredAudioProduct = preferredAudioProduct;
        this.preferredAudioDeviceId = preferredAudioDeviceId;
    }

    @Override
    public void addRecordingSurface(Surface surface) throws Exception {
        videoSource.addRecordingSurface(surface);
    }

    @Override
    public void addRecordingSurface(Surface surface, int width, int height) throws Exception {
        videoSource.addRecordingSurface(surface, width, height);
    }

    @Override
    public void removeRecordingSurface(Surface surface) {
        videoSource.removeRecordingSurface(surface);
    }

    @Override
    public void addDisplaySurface(Surface surface, int width, int height) throws Exception {
        videoSource.addDisplaySurface(surface, width, height);
    }

    @Override
    public void removeDisplaySurface(Surface surface) {
        videoSource.removeDisplaySurface(surface);
    }

    @Override
    public void setOutputRotation(int degrees) {
        videoSource.setOutputRotation(degrees);
    }

    @Override
    public int getOutputRotation() {
        return videoSource.getOutputRotation();
    }

    @Override
    public long getVideoTimelinePositionUs() {
        return videoSource.getVideoTimelinePositionUs();
    }

    @Override
    public int getTimelineGeneration() {
        return videoSource.getTimelineGeneration();
    }

    @Override
    public PcmAudioSubscription subscribeAudio(Context context) throws Exception {
        return preferredAudioDeviceId >= 0
                ? UsbAudioHub.subscribeExact(context, preferredAudioProduct, preferredAudioDeviceId)
                : UsbAudioHub.subscribeStrict(context, preferredAudioProduct);
    }

    @Override
    public boolean requiresRecordAudioPermission() {
        return videoSource.requiresRecordAudioPermission();
    }
}
