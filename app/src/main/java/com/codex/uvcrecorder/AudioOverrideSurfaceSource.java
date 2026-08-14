package com.codex.uvcrecorder;

import android.content.Context;
import android.view.Surface;

/** Delegates video to one UVC input while taking audio from the multi-device shared router. */
final class AudioOverrideSurfaceSource implements UvcSurfaceSource {
    private final UvcSurfaceSource videoSource;
    private final MultiAudioRouter audioRouter;

    AudioOverrideSurfaceSource(UvcSurfaceSource videoSource, MultiAudioRouter audioRouter) {
        this.videoSource = videoSource;
        this.audioRouter = audioRouter;
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
        return audioRouter.subscribe();
    }

    @Override
    public boolean requiresRecordAudioPermission() {
        return videoSource.requiresRecordAudioPermission();
    }
}
