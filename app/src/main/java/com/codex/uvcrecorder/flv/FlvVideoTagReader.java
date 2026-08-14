/* Copyright (C) 2016 The Android Open Source Project, Apache License 2.0. */
package com.codex.uvcrecorder.flv;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.AvcConfig;
import androidx.media3.extractor.HevcConfig;
import androidx.media3.extractor.TrackOutput;

final class FlvVideoTagReader extends FlvTagReader {
    static final int CODEC_AVC = 7;
    static final int CODEC_HEVC = 12;
    private static final int FRAME_KEY = 1;
    private static final int FRAME_INFO = 5;
    private static final int PACKET_SEQUENCE_HEADER = 0;
    private static final int PACKET_NALU = 1;

    private final ParsableByteArray nalStartCode =
            new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
    private final ParsableByteArray nalLength = new ParsableByteArray(4);
    private boolean outputFormat;
    private boolean outputKeyframe;
    private int frameType;
    private int codec;
    private int configuredCodec;
    private int nalLengthBytes;

    FlvVideoTagReader(TrackOutput output) {
        super(output);
    }

    static boolean supportsCodecId(int codecId) {
        return codecId == CODEC_AVC || codecId == CODEC_HEVC;
    }

    @Override
    void seek() {
        outputKeyframe = false;
    }

    @Override
    boolean parseHeader(ParsableByteArray data) throws ParserException {
        if (data.bytesLeft() < 1) {
            throw ParserException.createForMalformedContainer("Empty FLV video tag", null);
        }
        int header = data.readUnsignedByte();
        frameType = (header >> 4) & 0x0F;
        codec = header & 0x0F;
        if (!supportsCodecId(codec)) {
            throw new UnsupportedFormatException("FLV video format not supported: " + codec);
        }
        return frameType != FRAME_INFO;
    }

    @Override
    boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
        if (data.bytesLeft() < 4) {
            throw ParserException.createForMalformedContainer("Short FLV video packet", null);
        }
        int packetType = data.readUnsignedByte();
        int compositionTimeMs = data.readInt24();
        timeUs += compositionTimeMs * 1_000L;
        if (packetType == PACKET_SEQUENCE_HEADER
                && (!outputFormat || configuredCodec != codec)) {
            parseSequenceHeader(data);
            configuredCodec = codec;
            outputFormat = true;
            outputKeyframe = false;
            return false;
        }
        if (packetType != PACKET_NALU || !outputFormat) return false;
        boolean keyframe = frameType == FRAME_KEY;
        if (!outputKeyframe && !keyframe) return false;
        int bytesWritten = writeNalUnits(data);
        output.sampleMetadata(timeUs, keyframe ? C.BUFFER_FLAG_KEY_FRAME : 0,
                bytesWritten, 0, null);
        outputKeyframe = true;
        return true;
    }

    private void parseSequenceHeader(ParsableByteArray data) throws ParserException {
        byte[] bytes = new byte[data.bytesLeft()];
        data.readBytes(bytes, 0, bytes.length);
        ParsableByteArray configData = new ParsableByteArray(bytes);
        Format.Builder format = new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV);
        if (codec == CODEC_AVC) {
            AvcConfig config = AvcConfig.parse(configData);
            nalLengthBytes = config.nalUnitLengthFieldLength;
            format.setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setCodecs(config.codecs)
                    .setWidth(config.width)
                    .setHeight(config.height)
                    .setPixelWidthHeightRatio(config.pixelWidthHeightRatio)
                    .setInitializationData(config.initializationData);
        } else {
            HevcConfig config = HevcConfig.parse(configData);
            nalLengthBytes = config.nalUnitLengthFieldLength;
            format.setSampleMimeType(MimeTypes.VIDEO_H265)
                    .setCodecs(config.codecs)
                    .setWidth(config.width)
                    .setHeight(config.height)
                    .setPixelWidthHeightRatio(config.pixelWidthHeightRatio)
                    .setInitializationData(config.initializationData);
        }
        output.format(format.build());
    }

    private int writeNalUnits(ParsableByteArray data) throws ParserException {
        if (nalLengthBytes < 1 || nalLengthBytes > 4) {
            throw ParserException.createForMalformedContainer(
                    "Invalid FLV NAL length size: " + nalLengthBytes, null);
        }
        byte[] lengthData = nalLength.getData();
        lengthData[0] = 0;
        lengthData[1] = 0;
        lengthData[2] = 0;
        int padding = 4 - nalLengthBytes;
        int written = 0;
        while (data.bytesLeft() > 0) {
            if (data.bytesLeft() < nalLengthBytes) {
                throw ParserException.createForMalformedContainer(
                        "Truncated FLV NAL length", null);
            }
            data.readBytes(lengthData, padding, nalLengthBytes);
            nalLength.setPosition(0);
            int size = nalLength.readUnsignedIntToInt();
            if (size < 0 || size > data.bytesLeft()) {
                throw ParserException.createForMalformedContainer(
                        "Truncated FLV NAL unit: " + size, null);
            }
            nalStartCode.setPosition(0);
            output.sampleData(nalStartCode, 4);
            output.sampleData(data, size);
            written += 4 + size;
        }
        return written;
    }
}
