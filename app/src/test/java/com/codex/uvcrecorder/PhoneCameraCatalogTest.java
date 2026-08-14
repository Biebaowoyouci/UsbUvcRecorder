package com.codex.uvcrecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class PhoneCameraCatalogTest {
    @Test
    public void onlyRequestedResolutionAndFrameRateTiersAreVisible() {
        assertTrue(PhoneCameraCatalog.isVisibleResolution(3840, 2160));
        assertTrue(PhoneCameraCatalog.isVisibleResolution(1920, 1080));
        assertTrue(PhoneCameraCatalog.isVisibleResolution(1280, 720));
        assertFalse(PhoneCameraCatalog.isVisibleResolution(4096, 2160));
        assertFalse(PhoneCameraCatalog.isVisibleResolution(3264, 2448));
        assertTrue(PhoneCameraCatalog.isVisibleFrameRate(25));
        assertTrue(PhoneCameraCatalog.isVisibleFrameRate(30));
        assertTrue(PhoneCameraCatalog.isVisibleFrameRate(60));
        assertFalse(PhoneCameraCatalog.isVisibleFrameRate(24));
        assertFalse(PhoneCameraCatalog.isVisibleFrameRate(50));
    }

    @Test
    public void exactGlobalModeIsReusedOnAnotherCamera() {
        PhoneCameraCatalog.Mode expected = mode(1920, 1080, 60);
        PhoneCameraCatalog.Device camera = camera(
                mode(3840, 2160, 30), expected, mode(1280, 720, 60));

        assertEquals(expected,
                PhoneCameraCatalog.chooseMode(camera, new int[]{1920, 1080, 60}));
    }

    @Test
    public void unsupportedRateKeepsTheSameResolutionTier() {
        PhoneCameraCatalog.Mode expected = mode(3840, 2160, 30);
        PhoneCameraCatalog.Device camera = camera(
                expected, mode(1920, 1080, 25), mode(1280, 720, 25));

        assertEquals(expected,
                PhoneCameraCatalog.chooseMode(camera, new int[]{3840, 2160, 25}));
    }

    @Test
    public void unsupportedResolutionUsesTheNearestTier() {
        PhoneCameraCatalog.Mode expected = mode(1920, 1080, 60);
        PhoneCameraCatalog.Device camera = camera(
                expected, mode(1280, 720, 60), mode(1280, 720, 30));

        assertEquals(expected,
                PhoneCameraCatalog.chooseMode(camera, new int[]{3840, 2160, 60}));
    }

    private static PhoneCameraCatalog.Mode mode(int width, int height, int fps) {
        return new PhoneCameraCatalog.Mode(width, height, fps, null);
    }

    private static PhoneCameraCatalog.Device camera(PhoneCameraCatalog.Mode... modes) {
        List<PhoneCameraCatalog.Mode> values = Arrays.asList(modes);
        return new PhoneCameraCatalog.Device("test", "test", "", "test",
                1, 0, values, null);
    }
}
