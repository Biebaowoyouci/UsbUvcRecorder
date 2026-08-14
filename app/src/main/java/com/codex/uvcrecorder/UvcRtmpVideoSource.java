package com.codex.uvcrecorder;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.pedro.encoder.input.sources.OrientationConfig;
import com.pedro.encoder.input.sources.OrientationForced;
import com.pedro.encoder.input.sources.video.VideoSource;

/**
 * Bridges the app's already opened UVC/router source into RootEncoder without
 * taking screenshots or opening the capture card a second time.
 */
final class UvcRtmpVideoSource extends VideoSource {
    private final UvcSurfaceSource source;
    private Surface streamSurface;
    private boolean running;
    private int outputWidth;
    private int outputHeight;

    UvcRtmpVideoSource(UvcSurfaceSource source) {
        this.source = source;
    }

    @Override
    protected boolean create(int width, int height, int fps, int rotation) {
        outputWidth = width;
        outputHeight = height;
        return width > 0 && height > 0 && fps > 0;
    }

    /**
     * The UVC router has already applied the app's selected rotation and
     * center-crop before drawing into RootEncoder's input SurfaceTexture.
     * RootEncoder otherwise assumes a phone camera source and applies the
     * handset sensor orientation a second time. It also treats a portrait
     * encoder as landscape when the Activity is landscape, producing the
     * narrow image surrounded by black bars seen on the RTMP server.
     */
    @Override
    public OrientationConfig getOrientationConfig() {
        return new OrientationConfig(0, outputHeight > outputWidth,
                OrientationForced.NONE);
    }

    @Override
    public void start(SurfaceTexture surfaceTexture) {
        if (running) return;
        surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight);
        Surface surface = new Surface(surfaceTexture);
        try {
            source.addRecordingSurface(surface, outputWidth, outputHeight);
            streamSurface = surface;
            running = true;
        } catch (Exception error) {
            surface.release();
            throw new IllegalStateException("无法把当前视频信号接入 RTMP 编码器", error);
        }
    }

    @Override
    public void stop() {
        Surface surface = streamSurface;
        streamSurface = null;
        running = false;
        if (surface != null) {
            source.removeRecordingSurface(surface);
            surface.release();
        }
    }

    @Override
    public void release() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
