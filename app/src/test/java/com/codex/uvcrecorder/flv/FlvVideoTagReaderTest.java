package com.codex.uvcrecorder.flv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FlvVideoTagReaderTest {
    @Test
    public void acceptsStandardAvcAndDouyinLegacyHevcTags() {
        assertTrue(FlvVideoTagReader.supportsCodecId(7));
        assertTrue(FlvVideoTagReader.supportsCodecId(12));
        assertFalse(FlvVideoTagReader.supportsCodecId(2));
    }
}
