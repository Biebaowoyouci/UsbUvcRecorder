/*
 * Based on the AndroidX Media3 FLV extractor (Apache License 2.0).
 * This local variant keeps Media3's AAC/PCM pipeline while adding the
 * legacy codec-id 12 HEVC tags used by Douyin and other Chinese CDNs.
 */
package com.codex.uvcrecorder.flv;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;

import java.io.IOException;

/** Live FLV extractor supporting AAC plus H.264 or legacy codec-id 12 H.265. */
public final class CompatibleFlvExtractor implements Extractor {
    public static final ExtractorsFactory FACTORY =
            () -> new Extractor[]{new CompatibleFlvExtractor()};

    private static final int STATE_READING_HEADER = 1;
    private static final int STATE_SKIPPING_TO_TAG = 2;
    private static final int STATE_READING_TAG_HEADER = 3;
    private static final int STATE_READING_TAG_DATA = 4;
    private static final int FLV_HEADER_SIZE = 9;
    private static final int TAG_HEADER_SIZE = 11;
    private static final int TAG_AUDIO = 8;
    private static final int TAG_VIDEO = 9;
    private static final int FLV_SIGNATURE = 0x00464c56;

    private final ParsableByteArray scratch = new ParsableByteArray(4);
    private final ParsableByteArray header = new ParsableByteArray(FLV_HEADER_SIZE);
    private final ParsableByteArray tagHeader = new ParsableByteArray(TAG_HEADER_SIZE);
    private final ParsableByteArray tagData = new ParsableByteArray();

    private ExtractorOutput output;
    private FlvAudioTagReader audioReader;
    private FlvVideoTagReader videoReader;
    private int state = STATE_READING_HEADER;
    private int bytesToNextTag;
    private int tagType;
    private int tagDataSize;
    private long tagTimestampUs;
    private long timestampOffsetUs;
    private boolean outputFirstSample;

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        input.peekFully(scratch.getData(), 0, 3);
        scratch.setPosition(0);
        if (scratch.readUnsignedInt24() != FLV_SIGNATURE) return false;
        input.peekFully(scratch.getData(), 0, 2);
        scratch.setPosition(0);
        if ((scratch.readUnsignedShort() & 0xFA) != 0) return false;
        input.peekFully(scratch.getData(), 0, 4);
        scratch.setPosition(0);
        int dataOffset = scratch.readInt();
        input.resetPeekPosition();
        input.advancePeekPosition(dataOffset);
        input.peekFully(scratch.getData(), 0, 4);
        scratch.setPosition(0);
        return scratch.readInt() == 0;
    }

    @Override
    public void init(ExtractorOutput output) {
        this.output = output;
    }

    @Override
    public void seek(long position, long timeUs) {
        state = position == 0 ? STATE_READING_HEADER : STATE_READING_TAG_HEADER;
        bytesToNextTag = 0;
        if (position == 0) {
            outputFirstSample = false;
            timestampOffsetUs = 0;
        }
        if (audioReader != null) audioReader.seek();
        if (videoReader != null) videoReader.seek();
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        if (output == null) throw new IllegalStateException("Extractor is not initialized");
        while (true) {
            switch (state) {
                case STATE_READING_HEADER:
                    if (!readHeader(input)) return RESULT_END_OF_INPUT;
                    break;
                case STATE_SKIPPING_TO_TAG:
                    input.skipFully(bytesToNextTag);
                    bytesToNextTag = 0;
                    state = STATE_READING_TAG_HEADER;
                    break;
                case STATE_READING_TAG_HEADER:
                    if (!readTagHeader(input)) return RESULT_END_OF_INPUT;
                    break;
                case STATE_READING_TAG_DATA:
                    if (readTagData(input)) return RESULT_CONTINUE;
                    break;
                default:
                    throw new IllegalStateException("Unknown FLV extractor state");
            }
        }
    }

    @Override
    public void release() {
        // No native state.
    }

    private boolean readHeader(ExtractorInput input) throws IOException {
        if (!input.readFully(header.getData(), 0, FLV_HEADER_SIZE, true)) return false;
        header.setPosition(0);
        header.skipBytes(4);
        int flags = header.readUnsignedByte();
        if ((flags & 0x04) != 0 && audioReader == null) {
            audioReader = new FlvAudioTagReader(output.track(TAG_AUDIO, C.TRACK_TYPE_AUDIO));
        }
        if ((flags & 0x01) != 0 && videoReader == null) {
            videoReader = new FlvVideoTagReader(output.track(TAG_VIDEO, C.TRACK_TYPE_VIDEO));
        }
        output.endTracks();
        output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
        bytesToNextTag = header.readInt() - FLV_HEADER_SIZE + 4;
        state = STATE_SKIPPING_TO_TAG;
        return true;
    }

    private boolean readTagHeader(ExtractorInput input) throws IOException {
        if (!input.readFully(tagHeader.getData(), 0, TAG_HEADER_SIZE, true)) return false;
        tagHeader.setPosition(0);
        tagType = tagHeader.readUnsignedByte();
        tagDataSize = tagHeader.readUnsignedInt24();
        tagTimestampUs = tagHeader.readUnsignedInt24();
        tagTimestampUs = ((tagHeader.readUnsignedByte() << 24) | tagTimestampUs) * 1_000L;
        tagHeader.skipBytes(3);
        state = STATE_READING_TAG_DATA;
        return true;
    }

    private boolean readTagData(ExtractorInput input) throws IOException {
        boolean consumed = true;
        boolean sampleOutput = false;
        long timeUs = outputFirstSample ? timestampOffsetUs + tagTimestampUs : 0L;
        if (tagType == TAG_AUDIO && audioReader != null) {
            sampleOutput = audioReader.consume(prepareTagData(input), timeUs);
        } else if (tagType == TAG_VIDEO && videoReader != null) {
            sampleOutput = videoReader.consume(prepareTagData(input), timeUs);
        } else {
            input.skipFully(tagDataSize);
            consumed = false;
        }
        if (!outputFirstSample && sampleOutput) {
            outputFirstSample = true;
            timestampOffsetUs = -tagTimestampUs;
        }
        bytesToNextTag = 4;
        state = STATE_SKIPPING_TO_TAG;
        return consumed;
    }

    private ParsableByteArray prepareTagData(ExtractorInput input) throws IOException {
        if (tagDataSize > tagData.capacity()) {
            tagData.reset(new byte[Math.max(tagData.capacity() * 2, tagDataSize)], 0);
        } else {
            tagData.setPosition(0);
        }
        tagData.setLimit(tagDataSize);
        input.readFully(tagData.getData(), 0, tagDataSize);
        return tagData;
    }
}
