/* Copyright (C) 2016 The Android Open Source Project, Apache License 2.0. */
package com.codex.uvcrecorder.flv;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

abstract class FlvTagReader {
    static final class UnsupportedFormatException extends ParserException {
        UnsupportedFormatException(String message) {
            super(message, null, false, C.DATA_TYPE_MEDIA);
        }
    }

    final TrackOutput output;

    FlvTagReader(TrackOutput output) {
        this.output = output;
    }

    abstract void seek();

    final boolean consume(ParsableByteArray data, long timeUs) throws ParserException {
        return parseHeader(data) && parsePayload(data, timeUs);
    }

    abstract boolean parseHeader(ParsableByteArray data) throws ParserException;

    abstract boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException;
}
