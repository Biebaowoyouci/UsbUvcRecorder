package com.codex.uvcrecorder;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;

interface RecordingMuxer {
    int addTrack(MediaFormat format) throws IOException;

    void start() throws IOException;

    void writeSampleData(int trackIndex, ByteBuffer data, MediaCodec.BufferInfo info) throws IOException;

    void stop() throws IOException;

    void release();
}
