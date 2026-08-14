package com.codex.uvcrecorder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RtmpSettingsTest {
    @Test
    public void acceptsRtmpAndRtmpsPublishUrls() {
        assertTrue(SettingsActivity.isValidRtmpUrl(
                "rtmp://192.168.1.20/live/camera-1"));
        assertTrue(SettingsActivity.isValidRtmpUrl(
                "rtmps://push.example.com/app/private-key"));
    }

    @Test
    public void rejectsMissingSchemeHostOrApplicationPath() {
        assertFalse(SettingsActivity.isValidRtmpUrl(""));
        assertFalse(SettingsActivity.isValidRtmpUrl("http://example.com/live/key"));
        assertFalse(SettingsActivity.isValidRtmpUrl("rtmp://"));
        assertFalse(SettingsActivity.isValidRtmpUrl("rtmp://example.com"));
    }

    @Test
    public void validatesRtmpPullInputUrl() {
        assertTrue(SettingsActivity.isValidPullUrl(
                "rtmp://192.168.1.20/live/camera-1"));
        assertFalse(SettingsActivity.isValidPullUrl(
                "rtmps://push.example.com/live/camera-1"));
        assertTrue(SettingsActivity.isValidPullUrl(
                "http://example.com/live/camera-1"));
        assertTrue(SettingsActivity.isValidPullUrl(
                "https://example.com/live/camera.flv?token=abc"));
        assertFalse(SettingsActivity.isValidPullUrl("rtmp://example.com"));
        assertFalse(SettingsActivity.isValidPullUrl("file:///sdcard/test.flv"));
        assertTrue(NetworkStreamSource.isHttpFlvUrl(
                "HTTPS://example.com/live/camera.flv"));
        assertFalse(NetworkStreamSource.isHttpFlvUrl(
                "rtmp://example.com/live/camera"));
    }

    @Test
    public void capsLandscapeSignalWithoutChangingAspectRatio() {
        assertArrayEquals(new int[]{1920, 1080},
                RtmpStreamingController.outputSize(3840, 2160, 1080));
        assertArrayEquals(new int[]{1280, 720},
                RtmpStreamingController.outputSize(1920, 1080, 720));
        assertArrayEquals(new int[]{1080, 1920},
                RtmpStreamingController.outputSize(2160, 3840, 1080));
    }

    @Test
    public void followInputKeepsEvenEncoderDimensions() {
        assertArrayEquals(new int[]{3840, 2160},
                RtmpStreamingController.outputSize(3840, 2160, 0));
        assertArrayEquals(new int[]{1918, 1078},
                RtmpStreamingController.outputSize(1919, 1079, 0));
    }

    @Test
    public void portraitRotationUsesCenterCropInsteadOfAddingBlackBars() {
        float[] landscape = VideoLayout.centerCropScale(1920, 1080,
                0, 2400, 1080);
        assertEquals(1f, landscape[0], 0.0001f);
        assertTrue(landscape[1] > 1f);

        float[] rotatedPortrait = VideoLayout.centerCropScale(1920, 1080,
                90, 2400, 1080);
        assertEquals(1f, rotatedPortrait[0], 0.0001f);
        assertTrue(rotatedPortrait[1] > 1f);

        float[] portraitFile = VideoLayout.centerCropScale(1920, 1080,
                90, 1080, 1920);
        assertEquals(1f, portraitFile[0], 0.0001f);
        assertEquals(1f, portraitFile[1], 0.0001f);
    }

    @Test
    public void rtmpPcmChunksFitAacInputAndPreserveSampleFrames() {
        assertEquals(8192, UacRtmpAudioSource.alignedChunkBytes(4));
        assertEquals(8190, UacRtmpAudioSource.alignedChunkBytes(6));
        assertEquals(42_666,
                UacRtmpAudioSource.pcmDurationUs(8192, 48_000, 4));
    }

    @Test
    public void rtmpAudioClockRecoversFromDroppedPcmWithoutMovingBackwards() {
        assertEquals(1_000_000L, UacRtmpAudioSource.synchronizeTimestampUs(
                0, 1_000_000L, 20_000L));
        assertEquals(1_250_000L, UacRtmpAudioSource.synchronizeTimestampUs(
                1_020_000L, 1_250_000L, 20_000L));
        assertEquals(1_020_000L, UacRtmpAudioSource.synchronizeTimestampUs(
                1_020_000L, 800_000L, 20_000L));
        assertTrue(UacRtmpAudioSource.synchronizeTimestampUs(
                1_020_000L, 1_012_000L, 20_000L) > 1_000_000L);
    }

    @Test
    public void weakNetworkReconnectStartsImmediatelyThenAvoidsEncoderThrash() {
        assertEquals(0L, RtmpStreamingController.retryDelayMs(1));
        assertEquals(500L, RtmpStreamingController.retryDelayMs(2));
        assertEquals(1_000L, RtmpStreamingController.retryDelayMs(3));
        assertEquals(1_500L, RtmpStreamingController.retryDelayMs(50));
    }

    @Test
    public void pullStreamRejectsRepeatedOrBackwardsVideoTimestamps() {
        assertFalse(NetworkStreamSource.isRepeatedSurfaceTimestamp(0, 0));
        assertFalse(NetworkStreamSource.isRepeatedSurfaceTimestamp(1_000, 1_001));
        assertTrue(NetworkStreamSource.isRepeatedSurfaceTimestamp(1_000, 1_000));
        assertTrue(NetworkStreamSource.isRepeatedSurfaceTimestamp(1_000, 999));
        assertFalse(NetworkStreamSource.isRepeatedTimestampStalled(10_000, 11_499));
        assertTrue(NetworkStreamSource.isRepeatedTimestampStalled(10_000, 11_500));
    }

    @Test
    public void pullStreamDoesNotRestartDuringNormalFirstFrameStartup() {
        assertFalse(NetworkStreamSource.shouldReconnectBeforeFirstFrame(
                1_000, 0, 3_999));
        assertTrue(NetworkStreamSource.shouldReconnectBeforeFirstFrame(
                1_000, 0, 4_000));
    }

    @Test
    public void incomingNetworkDataExtendsFirstFrameDeadline() {
        assertFalse(NetworkStreamSource.shouldReconnectBeforeFirstFrame(
                1_000, 3_500, 6_499));
        assertTrue(NetworkStreamSource.shouldReconnectBeforeFirstFrame(
                1_000, 3_500, 6_500));
        assertTrue(NetworkStreamSource.shouldReconnectBeforeFirstFrame(
                1_000, 6_999, 7_000));
    }

    @Test
    public void establishedStreamStillReconnectsAfterFrameStall() {
        assertFalse(NetworkStreamSource.shouldReconnectAfterFirstFrame(
                10_000, 12_999));
        assertTrue(NetworkStreamSource.shouldReconnectAfterFirstFrame(
                10_000, 13_000));
    }

    @Test
    public void pullRecordingPausesAudioWheneverVideoStops() {
        assertFalse(NetworkStreamSource.shouldRecordDecodedAudio(false, false));
        assertFalse(NetworkStreamSource.shouldRecordDecodedAudio(true, true));
        assertTrue(NetworkStreamSource.shouldRecordDecodedAudio(true, false));
        assertFalse(NetworkStreamSource.isAudioVideoFlowGap(
                10_000L, 10_250L, 250L));
        assertTrue(NetworkStreamSource.isAudioVideoFlowGap(
                10_000L, 10_251L, 250L));
        assertFalse(SurfaceRecorder.isForwardVideoStall(
                Long.MIN_VALUE, 1_000_000L, 250_000L));
        assertFalse(SurfaceRecorder.isForwardVideoStall(
                1_000_000L, 1_250_000L, 250_000L));
        assertTrue(SurfaceRecorder.isForwardVideoStall(
                1_000_000L, 1_250_001L, 250_000L));
    }

    @Test
    public void displayRotationCounterRotatesPreviewButNotEncodedPixels() {
        assertEquals(0, MainActivity.displayRotationCompensationDegrees(1, 1));
        assertEquals(180, MainActivity.displayRotationCompensationDegrees(1, 3));
        assertEquals(180, MainActivity.displayRotationCompensationDegrees(3, 1));
        assertEquals(270, MainActivity.displayRotationCompensationDegrees(0, 1));
    }

    @Test
    public void portraitLaunchTransitionIsNotAppliedToLandscapePreview() {
        assertEquals(0, MainActivity.landscapeDisplayCompensationDegrees(2, 1));
        assertEquals(0, MainActivity.landscapeDisplayCompensationDegrees(0, 3));
        assertEquals(180, MainActivity.landscapeDisplayCompensationDegrees(1, 3));
        assertEquals(180, MainActivity.landscapeDisplayCompensationDegrees(3, 1));
    }

    @Test
    public void videoRotationProfilesNormalizeIndependentQuarterTurns() {
        assertEquals(0, AppSettings.normalizeRotation(0));
        assertEquals(90, AppSettings.normalizeRotation(450));
        assertEquals(270, AppSettings.normalizeRotation(-90));
        assertEquals(0, AppSettings.normalizeRotation(45));
    }

    @Test
    public void pullStreamDropsOldTimelineAndCapsDecodedAudioQueue() {
        assertTrue(NetworkStreamSource.isBackwardTimelineJump(1_000, 700));
        assertFalse(NetworkStreamSource.isBackwardTimelineJump(1_000, 751));
        assertFalse(NetworkStreamSource.shouldHardResyncLiveOffset(
                androidx.media3.common.C.TIME_UNSET));
        assertFalse(NetworkStreamSource.shouldHardResyncLiveOffset(3_499));
        assertTrue(NetworkStreamSource.shouldHardResyncLiveOffset(3_500));
        assertEquals(48_000,
                NetworkStreamSource.maxLiveAudioQueueBytes(48_000, 2, 2));
    }

    @Test
    public void pullStreamMeasuresSourceFrameRateFromRenderedFrames() {
        assertEquals(25, NetworkStreamSource.estimateRenderedFps(39, 1_520));
        assertEquals(60, NetworkStreamSource.estimateRenderedFps(91, 1_500));
        assertEquals(0, NetworkStreamSource.estimateRenderedFps(1, 1_500));
    }

    @Test
    public void recordingKeepsAudioAndVideoOnOneSourceTimeline() {
        assertTrue(SurfaceRecorder.timestampsShareClock(
                1_000_000L, 1_033_333L));
        assertFalse(SurfaceRecorder.timestampsShareClock(
                2_000_000_000L, 1_033_333L));
        assertEquals(-1_000_000L,
                SurfaceRecorder.initialTimelineAdjustmentUs(
                        1_000_000L, 1_033_333L));
        assertEquals(-1_033_333L,
                SurfaceRecorder.initialTimelineAdjustmentUs(
                        2_000_000_000L, 1_033_333L));
        assertEquals(-1_033_333L,
                SurfaceRecorder.initialAudioTimelineAdjustmentUs(
                        1_000_000L, 1_033_333L, true));
        assertEquals(-1_000_000L,
                SurfaceRecorder.initialAudioTimelineAdjustmentUs(
                        1_000_000L, 1_033_333L, false));
        assertEquals(1_250_000L,
                SurfaceRecorder.monotonicAudioInputPtsUs(
                        1_250_000L, 1_020_000L));
        assertEquals(1_020_000L,
                SurfaceRecorder.monotonicAudioInputPtsUs(
                        900_000L, 1_020_000L));
        assertTrue(SurfaceRecorder.isSourceTimestampReset(
                2_000_000L, 1_700_000L));
        assertFalse(SurfaceRecorder.isSourceTimestampReset(
                2_000_000L, 1_750_000L));
        assertFalse(SurfaceRecorder.isSourceTimestampReset(
                Long.MIN_VALUE, 0L));
    }

    @Test
    public void recordingMaterializesMissingPcmTimeInsteadOfCompressingIt() {
        assertEquals(5_000_000L, SurfaceRecorder.audioGapToFillUs(
                6_000_000L, 1_000_000L, 10_000L));
        assertEquals(0L, SurfaceRecorder.audioGapToFillUs(
                1_009_000L, 1_000_000L, 10_000L));
        assertEquals(500_000L, SurfaceRecorder.audioOverlapToDropUs(
                1_000_000L, 1_500_000L, 10_000L));
        assertEquals(0L, SurfaceRecorder.audioOverlapToDropUs(
                1_495_000L, 1_500_000L, 10_000L));
    }

    @Test
    public void mobilePushStartsWithinBandwidthAndAdaptsBeforeCacheOverflows() {
        assertEquals(6_000_000,
                RtmpStreamingController.initialVideoBitrateBps(
                        6_000_000, 13_774));
        assertEquals(1_300_000,
                RtmpStreamingController.initialVideoBitrateBps(
                        6_000_000, 2_000));
        assertEquals(2_800_000,
                RtmpStreamingController.emergencyVideoBitrateBps(
                        6_000_000, 4_000_000));
        assertEquals(800,
                RtmpStreamingController.interleaveDelayMs(true, false));
        assertEquals(350,
                RtmpStreamingController.interleaveDelayMs(false, true));
        assertFalse(RtmpStreamingController.shouldReduceBitrate(
                0, 400, 0, false, 6_000_000L, 6_000_000));
        assertTrue(RtmpStreamingController.shouldReduceBitrate(
                4, 400, 0, false, 6_000_000L, 6_000_000));
        assertTrue(RtmpStreamingController.shouldReduceBitrate(
                40, 400, 40, false, 6_000_000L, 6_000_000));
        assertTrue(RtmpStreamingController.shouldReduceBitrate(
                1, 400, 1, false, 3_000_000L, 6_000_000));
        assertEquals(3_000_000,
                RtmpStreamingController.congestionTargetBitrateBps(
                        6_000_000, 6_000_000, 4_000_000L));
    }
}
