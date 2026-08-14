package com.codex.uvcrecorder;

import android.hardware.camera2.CameraCharacteristics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoLayoutTest {
    @Test
    public void fillKeepsUniformPixelScaleForFourByThreeCameraOnWideScreen() {
        float[] scale = VideoLayout.centerCropScale(4096, 3072,
                0, 2400, 1080);
        assertEquals(1f, scale[0], 0.0001f);
        assertEquals(2400f / 1080f / (4096f / 3072f), scale[1], 0.0001f);
    }

    @Test
    public void fitKeepsCompleteFourByThreeCameraImage() {
        float[] scale = VideoLayout.fitCenterScale(4096, 3072,
                0, 2400, 1080);
        assertEquals((4096f / 3072f) / (2400f / 1080f), scale[0], 0.0001f);
        assertEquals(1f, scale[1], 0.0001f);
    }

    @Test
    public void uvcPreviewFitNeverCropsWideSignalOnNarrowScreen() {
        float[] scale = VideoLayout.uvcPreviewScale(1920, 1080,
                0, 2000, 1200);
        assertEquals(1f, scale[0], 0.0001f);
        assertTrue(scale[1] < 1f);
    }

    @Test
    public void quarterTurnExpandsPackedPortraitWithoutStretchingVisiblePicture() {
        float[] scale = VideoLayout.uvcPreviewScale(3840, 2160,
                90, 3008, 1880);
        assertEquals(1f, scale[0], 0.0001f);
        assertEquals((3008f / 1880f) / (2160f / 3840f),
                scale[1], 0.0001f);

        // A 9:16 active portrait image packed into the 16:9 raster becomes a
        // complete 16:9 image after rotation: full target width, no active crop.
        float packedActiveFraction = (2160f * 9f / 16f) / 3840f;
        float activeHeight = 1880f * scale[1] * packedActiveFraction;
        assertEquals(3008f * 9f / 16f, activeHeight, 0.5f);
        assertTrue(activeHeight < 1880f);
    }

    @Test
    public void nativePortraitUvcQuarterTurnStillFitsCompleteFrame() {
        float[] scale = VideoLayout.uvcPreviewScale(1080, 1920,
                90, 3008, 1880);
        assertEquals(1f, scale[0], 0.0001f);
        assertTrue(scale[1] < 1f);
    }

    @Test
    public void quarterTurnUsesRotatedAspectRatio() {
        float[] scale = VideoLayout.centerCropScale(1920, 1080,
                90, 2400, 1080);
        assertEquals(1f, scale[0], 0.0001f);
        assertTrue(scale[1] > 3f);
    }

    @Test
    public void logarithmicExposureSliderRoundTrips() {
        long minimum = 25_000L;
        long maximum = 33_333_333L;
        long target = 10_000_000L;
        int progress = PhoneCameraControls.longToProgress(target, minimum, maximum);
        long restored = PhoneCameraControls.progressToLong(progress, minimum, maximum);
        assertEquals(target, restored, target / 20);
    }

    @Test
    public void phoneCameraNormalizationIsIndependentOfDisplayRotation() {
        assertEquals(0, PhoneCameraSource.normalizedFrameRotation(
                CameraCharacteristics.LENS_FACING_BACK));
        assertEquals(180, PhoneCameraSource.normalizedFrameRotation(
                CameraCharacteristics.LENS_FACING_FRONT));
        assertEquals(0, PhoneCameraSource.normalizedFrameRotation(
                CameraCharacteristics.LENS_FACING_EXTERNAL));
    }
}
