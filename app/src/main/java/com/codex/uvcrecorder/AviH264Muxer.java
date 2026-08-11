package com.codex.uvcrecorder;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact AVI 1.0 muxer for H.264 access units. AVI is limited to about 4 GB, so callers should
 * use segmentation for very high bitrates. SPS/PPS are repeated on keyframes for player support.
 */
final class AviH264Muxer implements RecordingMuxer {
    private static final long SAFE_FILE_LIMIT = 0xF0000000L;
    private final FileOutputStream stream;
    private final FileChannel channel;
    private final List<IndexEntry> index = new ArrayList<>();

    private int width;
    private int height;
    private int fps;
    private int bitRate;
    private int frameCount;
    private int maxFrameSize;
    private final String codecFourCc;
    private byte[] codecConfig = new byte[0];
    private boolean started;
    private long riffSizePosition;
    private long totalFramesPosition;
    private long streamLengthPosition;
    private long moviSizePosition;
    private long moviDataStart;

    AviH264Muxer(FileDescriptor descriptor, String videoMime) {
        stream = new FileOutputStream(descriptor);
        channel = stream.getChannel();
        codecFourCc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime) ? "HEVC" : "H264";
    }

    @Override
    public int addTrack(MediaFormat format) {
        width = format.getInteger(MediaFormat.KEY_WIDTH);
        height = format.getInteger(MediaFormat.KEY_HEIGHT);
        fps = format.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? Math.max(1, format.getInteger(MediaFormat.KEY_FRAME_RATE)) : 30;
        bitRate = format.containsKey(MediaFormat.KEY_BIT_RATE)
                ? format.getInteger(MediaFormat.KEY_BIT_RATE) : width * height * fps;
        codecConfig = readCodecConfig(format);
        return 0;
    }

    @Override
    public void start() throws IOException {
        channel.truncate(0);
        channel.position(0);
        writeFourCc("RIFF");
        riffSizePosition = channel.position();
        writeInt(0);
        writeFourCc("AVI ");

        writeFourCc("LIST");
        writeInt(192);
        writeFourCc("hdrl");

        writeFourCc("avih");
        writeInt(56);
        writeInt(Math.max(1, 1_000_000 / fps));
        writeInt(Math.max(1, bitRate / 8));
        writeInt(0);
        writeInt(0x10);
        totalFramesPosition = channel.position();
        writeInt(0);
        writeInt(0);
        writeInt(1);
        writeInt(Math.max(64 * 1024, width * height));
        writeInt(width);
        writeInt(height);
        writeInt(0); writeInt(0); writeInt(0); writeInt(0);

        writeFourCc("LIST");
        writeInt(116);
        writeFourCc("strl");
        writeFourCc("strh");
        writeInt(56);
        writeFourCc("vids");
        writeFourCc(codecFourCc);
        writeInt(0);
        writeShort(0); writeShort(0);
        writeInt(0);
        writeInt(1_000);
        writeInt(fps * 1_000);
        writeInt(0);
        streamLengthPosition = channel.position();
        writeInt(0);
        writeInt(Math.max(64 * 1024, width * height));
        writeInt(-1);
        writeInt(0);
        writeShort(0); writeShort(0); writeShort(width); writeShort(height);

        writeFourCc("strf");
        writeInt(40);
        writeInt(40);
        writeInt(width);
        writeInt(height);
        writeShort(1);
        writeShort(24);
        writeFourCc(codecFourCc);
        writeInt(width * height * 3);
        writeInt(0); writeInt(0); writeInt(0); writeInt(0);

        writeFourCc("LIST");
        moviSizePosition = channel.position();
        writeInt(0);
        writeFourCc("movi");
        moviDataStart = channel.position();
        started = true;
    }

    @Override
    public void writeSampleData(int trackIndex, ByteBuffer data, MediaCodec.BufferInfo info) throws IOException {
        if (!started || info.size <= 0) return;
        byte[] sample = readBytes(data, info.offset, info.size);
        sample = toAnnexB(sample);
        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (keyFrame && codecConfig.length > 0) {
            byte[] joined = new byte[codecConfig.length + sample.length];
            System.arraycopy(codecConfig, 0, joined, 0, codecConfig.length);
            System.arraycopy(sample, 0, joined, codecConfig.length, sample.length);
            sample = joined;
        }
        long chunkStart = channel.position();
        if (chunkStart + sample.length + 32L > SAFE_FILE_LIMIT) {
            throw new FileLimitReachedException();
        }
        writeFourCc("00dc");
        writeInt(sample.length);
        writeFully(ByteBuffer.wrap(sample));
        if ((sample.length & 1) != 0) writeByte(0);
        index.add(new IndexEntry(chunkStart - (moviDataStart - 4), sample.length, keyFrame));
        frameCount++;
        maxFrameSize = Math.max(maxFrameSize, sample.length);
    }

    @Override
    public void stop() throws IOException {
        if (!started) return;
        long indexStart = channel.position();
        writeFourCc("idx1");
        writeInt(index.size() * 16);
        for (IndexEntry entry : index) {
            writeFourCc("00dc");
            writeInt(entry.keyFrame ? 0x10 : 0);
            writeInt((int) entry.offset);
            writeInt(entry.size);
        }
        long fileSize = channel.position();
        patchInt(riffSizePosition, (int) (fileSize - 8));
        patchInt(totalFramesPosition, frameCount);
        patchInt(streamLengthPosition, frameCount);
        patchInt(moviSizePosition, (int) (indexStart - (moviSizePosition + 4)));
        channel.force(true);
        started = false;
    }

    @Override
    public void release() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private void patchInt(long position, int value) throws IOException {
        long current = channel.position();
        channel.position(position);
        writeInt(value);
        channel.position(current);
    }

    private void writeFourCc(String value) throws IOException {
        writeFully(ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    private void writeByte(int value) throws IOException {
        writeFully(ByteBuffer.wrap(new byte[]{(byte) value}));
    }

    private void writeShort(int value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value);
        buffer.flip();
        writeFully(buffer);
    }

    private void writeInt(int value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
        buffer.flip();
        writeFully(buffer);
    }

    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static byte[] readBytes(ByteBuffer source, int offset, int length) {
        ByteBuffer copy = source.duplicate();
        copy.position(offset);
        copy.limit(offset + length);
        byte[] bytes = new byte[length];
        copy.get(bytes);
        return bytes;
    }

    private static byte[] readCodecConfig(MediaFormat format) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        appendBuffer(output, format.getByteBuffer("csd-0"));
        appendBuffer(output, format.getByteBuffer("csd-1"));
        appendBuffer(output, format.getByteBuffer("csd-2"));
        return toAnnexB(output.toByteArray());
    }

    private static void appendBuffer(ByteArrayOutputStream output, ByteBuffer buffer) {
        if (buffer == null) return;
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        output.write(bytes, 0, bytes.length);
    }

    private static byte[] toAnnexB(byte[] input) {
        if (input.length < 4 || hasStartCode(input, 0)) return input;
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length + 32);
        int offset = 0;
        boolean parsed = false;
        while (offset + 4 <= input.length) {
            int length = ((input[offset] & 0xff) << 24) | ((input[offset + 1] & 0xff) << 16)
                    | ((input[offset + 2] & 0xff) << 8) | (input[offset + 3] & 0xff);
            if (length <= 0 || offset + 4 + length > input.length) break;
            output.write(0); output.write(0); output.write(0); output.write(1);
            output.write(input, offset + 4, length);
            offset += 4 + length;
            parsed = true;
        }
        if (parsed && offset == input.length) return output.toByteArray();
        output.reset();
        output.write(0); output.write(0); output.write(0); output.write(1);
        output.write(input, 0, input.length);
        return output.toByteArray();
    }

    private static boolean hasStartCode(byte[] value, int offset) {
        return value.length >= offset + 4 && value[offset] == 0 && value[offset + 1] == 0
                && (value[offset + 2] == 1 || (value[offset + 2] == 0 && value[offset + 3] == 1));
    }

    private static final class IndexEntry {
        final long offset;
        final int size;
        final boolean keyFrame;

        IndexEntry(long offset, int size, boolean keyFrame) {
            this.offset = offset;
            this.size = size;
            this.keyFrame = keyFrame;
        }
    }

    static final class FileLimitReachedException extends IOException {
        FileLimitReachedException() {
            super("AVI 文件达到安全上限，自动开始下一分段");
        }
    }
}
