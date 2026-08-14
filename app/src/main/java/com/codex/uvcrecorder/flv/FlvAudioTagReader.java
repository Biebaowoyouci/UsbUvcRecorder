/* Copyright (C) 2016 The Android Open Source Project, Apache License 2.0. */
package com.codex.uvcrecorder.flv;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.AacUtil;
import androidx.media3.extractor.TrackOutput;

import java.util.Collections;

final class FlvAudioTagReader extends FlvTagReader {
    private static final int FORMAT_MP3 = 2;
    private static final int FORMAT_ALAW = 7;
    private static final int FORMAT_ULAW = 8;
    private static final int FORMAT_AAC = 10;
    private static final int AAC_SEQUENCE_HEADER = 0;
    private static final int AAC_RAW = 1;
    private static final int[] SAMPLE_RATES = {5512, 11025, 22050, 44100};

    private boolean parsedHeader;
    private boolean outputFormat;
    private int audioFormat;

    FlvAudioTagReader(TrackOutput output) {
        super(output);
    }

    @Override
    void seek() {
        // Stream format is retained across a live reconnect seek.
    }

    @Override
    boolean parseHeader(ParsableByteArray data) throws ParserException {
        if (data.bytesLeft() < 1) {
            throw ParserException.createForMalformedContainer("Empty FLV audio tag", null);
        }
        if (!parsedHeader) {
            int header = data.readUnsignedByte();
            audioFormat = (header >> 4) & 0x0F;
            if (audioFormat == FORMAT_MP3) {
                output.format(new Format.Builder()
                        .setContainerMimeType(MimeTypes.VIDEO_FLV)
                        .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                        .setChannelCount(1)
                        .setSampleRate(SAMPLE_RATES[(header >> 2) & 0x03])
                        .build());
                outputFormat = true;
            } else if (audioFormat == FORMAT_ALAW || audioFormat == FORMAT_ULAW) {
                output.format(new Format.Builder()
                        .setContainerMimeType(MimeTypes.VIDEO_FLV)
                        .setSampleMimeType(audioFormat == FORMAT_ALAW
                                ? MimeTypes.AUDIO_ALAW : MimeTypes.AUDIO_MLAW)
                        .setChannelCount(1)
                        .setSampleRate(8000)
                        .build());
                outputFormat = true;
            } else if (audioFormat != FORMAT_AAC) {
                throw new UnsupportedFormatException(
                        "FLV audio format not supported: " + audioFormat);
            }
            parsedHeader = true;
        } else {
            data.skipBytes(1);
        }
        return true;
    }

    @Override
    boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
        if (audioFormat == FORMAT_MP3) {
            return writeSample(data, timeUs);
        }
        if (data.bytesLeft() < 1) {
            throw ParserException.createForMalformedContainer("Missing FLV AAC packet type", null);
        }
        int packetType = data.readUnsignedByte();
        if (packetType == AAC_SEQUENCE_HEADER && !outputFormat) {
            byte[] configBytes = new byte[data.bytesLeft()];
            data.readBytes(configBytes, 0, configBytes.length);
            AacUtil.Config config = AacUtil.parseAudioSpecificConfig(configBytes);
            output.format(new Format.Builder()
                    .setContainerMimeType(MimeTypes.VIDEO_FLV)
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setCodecs(config.codecs)
                    .setChannelCount(config.channelCount)
                    .setSampleRate(config.sampleRateHz)
                    .setInitializationData(Collections.singletonList(configBytes))
                    .build());
            outputFormat = true;
            return false;
        }
        return (audioFormat != FORMAT_AAC || packetType == AAC_RAW)
                && writeSample(data, timeUs);
    }

    private boolean writeSample(ParsableByteArray data, long timeUs) {
        int size = data.bytesLeft();
        output.sampleData(data, size);
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null);
        return true;
    }
}
