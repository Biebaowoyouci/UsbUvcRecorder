package com.codex.uvcrecorder;

/** Pure aspect-ratio calculations shared by preview, recording and tests. */
final class VideoLayout {
    private VideoLayout() {
    }

    /**
     * Returns X/Y MVP scale factors for center-crop ("fill") rendering.
     * One axis remains 1 and the other grows beyond 1, so no black bars are
     * introduced and the image is never stretched non-uniformly.
     */
    static float[] centerCropScale(int sourceWidth, int sourceHeight, int rotation,
                                   int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new float[]{1f, 1f};
        }
        boolean quarterTurn = Math.floorMod(rotation, 180) != 0;
        float rotatedWidth = quarterTurn ? sourceHeight : sourceWidth;
        float rotatedHeight = quarterTurn ? sourceWidth : sourceHeight;
        float sourceAspect = rotatedWidth / rotatedHeight;
        float targetAspect = targetWidth / (float) targetHeight;
        if (targetAspect > sourceAspect) {
            return new float[]{1f, targetAspect / sourceAspect};
        }
        return new float[]{sourceAspect / targetAspect, 1f};
    }

    /** Returns X/Y scale factors for an undistorted complete-image fit. */
    static float[] fitCenterScale(int sourceWidth, int sourceHeight, int rotation,
                                  int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new float[]{1f, 1f};
        }
        boolean quarterTurn = Math.floorMod(rotation, 180) != 0;
        float rotatedWidth = quarterTurn ? sourceHeight : sourceWidth;
        float rotatedHeight = quarterTurn ? sourceWidth : sourceHeight;
        float sourceAspect = rotatedWidth / rotatedHeight;
        float targetAspect = targetWidth / (float) targetHeight;
        if (targetAspect > sourceAspect) {
            return new float[]{sourceAspect / targetAspect, 1f};
        }
        return new float[]{1f, targetAspect / sourceAspect};
    }

    /**
     * UVC portrait signals are commonly packed into a landscape 16:9 raster with
     * black pillars. After a quarter turn, fitting that outer raster preserves the
     * encoded bars and reduces the visible picture to a small centre rectangle.
     * Fill only for that packed-landscape quarter-turn case; a genuinely portrait
     * UVC mode and every 0/180-degree preview still show the complete raster.
     */
    static float[] uvcPreviewScale(int sourceWidth, int sourceHeight, int rotation,
                                   int targetWidth, int targetHeight) {
        boolean quarterTurn = Math.floorMod(rotation, 180) != 0;
        boolean packedLandscape = sourceWidth > sourceHeight;
        return quarterTurn && packedLandscape
                ? centerCropScale(sourceWidth, sourceHeight, rotation,
                targetWidth, targetHeight)
                : fitCenterScale(sourceWidth, sourceHeight, rotation,
                targetWidth, targetHeight);
    }
}
