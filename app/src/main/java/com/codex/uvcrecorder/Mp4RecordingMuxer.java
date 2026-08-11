package com.codex.uvcrecorder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

final class Mp4RecordingMuxer implements RecordingMuxer {
    private final MediaMuxer muxer;
    private boolean started;

    Mp4RecordingMuxer(FileDescriptor descriptor) throws IOException {
        muxer = new MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    @Override
    public int addTrack(MediaFormat format) {
        return muxer.addTrack(format);
    }

    @Override
    public void start() {
        muxer.start();
        started = true;
    }

    @Override
    public void writeSampleData(int trackIndex, ByteBuffer data, MediaCodec.BufferInfo info) {
        if (started && trackIndex >= 0) muxer.writeSampleData(trackIndex, data, info);
    }

    @Override
    public void stop() {
        if (started) {
            muxer.stop();
            started = false;
        }
    }

    @Override
    public void release() {
        try {
            muxer.release();
        } catch (Exception ignored) {
        }
    }
}
