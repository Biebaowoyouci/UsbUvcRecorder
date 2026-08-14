package com.codex.uvcrecorder;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import com.serenegiant.opengl.EGLBase;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Converts Camera2 YUV_420_888 frames into the router input surface.
 *
 * <p>The shaders, vertex order and texture coordinates intentionally match the
 * reference APK's NV12 OpenGL renderer. Camera2 never writes directly into the
 * preview/encoder surface; the same normalized frame is therefore used by the
 * preview, recorder and RTMP output.</p>
 */
final class Yuv420SurfaceRenderer {
    private static final float[] VERTICES = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    // Reference APK: AbstractC5009a.f18214B.
    private static final float[] TEXTURE_COORDINATES = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    // Reference APK: shader/Vertex/YUV420sp.vert.
    private static final String VERTEX_SHADER =
            "varying vec2 textureCoordinate;\n"
                    + "attribute vec4 vPosition;\n"
                    + "attribute vec2 inputTextureCoordinate;\n"
                    + "uniform mat4 vMatrix;\n"
                    + "void main(){\n"
                    + "  gl_Position = vMatrix * vPosition;\n"
                    + "  textureCoordinate = inputTextureCoordinate;\n"
                    + "}\n";

    // Reference APK: shader/Fragment/NV12ToRGB.frag.
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "varying vec2 textureCoordinate;\n"
                    + "uniform sampler2D y_tex;\n"
                    + "uniform sampler2D uv_tex;\n"
                    + "void main() {\n"
                    + "  float y, u, v, r, g, b;\n"
                    + "  y = texture2D(y_tex, textureCoordinate).r;\n"
                    + "  u = texture2D(uv_tex, textureCoordinate).r - 0.5;\n"
                    + "  v = texture2D(uv_tex, textureCoordinate).a - 0.5;\n"
                    + "  r = y + 1.28033*v;\n"
                    + "  g = y - 0.21482*u - 0.38059*v;\n"
                    + "  b = y + 2.12798*u;\n"
                    + "  gl_FragColor = vec4(r, g, b, 1.0);\n"
                    + "}\n";

    private final int outputWidth;
    private final int outputHeight;
    private final EGLBase egl;
    private final EGLBase.IEglSurface eglSurface;
    private final FloatBuffer vertexBuffer;
    private final FloatBuffer textureBuffer;
    private final float[] matrix = new float[16];
    private final int[] textures = new int[2];

    private int program;
    private int positionLocation;
    private int textureCoordinateLocation;
    private int matrixLocation;
    private int ySamplerLocation;
    private int uvSamplerLocation;
    private ByteBuffer yData;
    private ByteBuffer uvData;
    private boolean released;

    Yuv420SurfaceRenderer(Surface target, int outputWidth, int outputHeight) {
        if (target == null || !target.isValid()) {
            throw new IllegalArgumentException("YUV output surface is unavailable");
        }
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        vertexBuffer = floatBuffer(VERTICES);
        textureBuffer = floatBuffer(TEXTURE_COORDINATES);
        egl = EGLBase.createFrom(null, 2, false, 0, true);
        eglSurface = egl.createFromSurface(target);
        eglSurface.makeCurrent();
        createGlObjects();
    }

    void render(Image image, int rotationDegrees, boolean mirrorHorizontally) {
        if (released || image == null || image.getFormat() != ImageFormat.YUV_420_888) return;
        Rect crop = image.getCropRect();
        int width = crop.width() & ~1;
        int height = crop.height() & ~1;
        if (width <= 0 || height <= 0) return;

        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int chromaSize = width * chromaHeight;
        yData = ensureCapacity(yData, ySize);
        uvData = ensureCapacity(uvData, chromaSize);

        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return;
        copyPlane(planes[0], crop.left, crop.top, width, height, yData);
        copyNv12(planes[1], crop.left / 2, crop.top / 2,
                width, chromaHeight, uvData);

        eglSurface.makeCurrent();
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        Matrix.setIdentityM(matrix, 0);
        if (mirrorHorizontally) {
            // Camera2 ImageReader frames from the front sensor on some devices
            // retain the selfie-preview mirror. Normalize the source before it
            // reaches preview, recording, RTMP and HDMI consumers.
            Matrix.scaleM(matrix, 0, -1f, 1f, 1f);
        }
        if (rotationDegrees != 0) {
            Matrix.rotateM(matrix, 0, Math.floorMod(rotationDegrees, 360),
                    0f, 0f, -1f);
        }
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);

        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT,
                false, 0, vertexBuffer);
        textureBuffer.position(0);
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation);
        GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT,
                false, 0, textureBuffer);

        uploadPlane(0, textures[0], ySamplerLocation, width, height,
                GLES20.GL_LUMINANCE, yData);
        uploadPlane(1, textures[1], uvSamplerLocation,
                chromaWidth, chromaHeight, GLES20.GL_LUMINANCE_ALPHA, uvData);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionLocation);
        GLES20.glDisableVertexAttribArray(textureCoordinateLocation);
        eglSurface.swap(image.getTimestamp());
    }

    void release() {
        if (released) return;
        released = true;
        try {
            eglSurface.makeCurrent();
            if (program != 0) GLES20.glDeleteProgram(program);
            GLES20.glDeleteTextures(textures.length, textures, 0);
        } catch (Throwable ignored) {
        }
        try {
            eglSurface.release();
        } catch (Throwable ignored) {
        }
        try {
            egl.release();
        } catch (Throwable ignored) {
        }
    }

    private void createGlObjects() {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        String log = GLES20.glGetProgramInfoLog(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("YUV shader link failed: " + log);
        }
        positionLocation = GLES20.glGetAttribLocation(program, "vPosition");
        textureCoordinateLocation =
                GLES20.glGetAttribLocation(program, "inputTextureCoordinate");
        matrixLocation = GLES20.glGetUniformLocation(program, "vMatrix");
        ySamplerLocation = GLES20.glGetUniformLocation(program, "y_tex");
        uvSamplerLocation = GLES20.glGetUniformLocation(program, "uv_tex");

        GLES20.glGenTextures(textures.length, textures, 0);
        for (int texture : textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static void uploadPlane(int unit, int texture, int sampler,
                                    int width, int height, int format,
                                    ByteBuffer data) {
        data.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format,
                width, height, 0, format,
                GLES20.GL_UNSIGNED_BYTE, data);
        GLES20.glUniform1i(sampler, unit);
    }

    private static ByteBuffer ensureCapacity(ByteBuffer current, int capacity) {
        if (current == null || current.capacity() != capacity) {
            current = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        }
        current.clear();
        return current;
    }

    private static void copyPlane(Image.Plane plane, int cropX, int cropY,
                                  int width, int height, ByteBuffer output) {
        ByteBuffer input = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int base = input.position() + cropY * rowStride + cropX * pixelStride;
        int limit = input.limit();
        output.clear();
        if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                int start = base + row * rowStride;
                int count = Math.min(width, Math.max(0, limit - start));
                if (count <= 0) break;
                input.position(start);
                input.limit(start + count);
                output.put(input);
                input.limit(limit);
                for (int x = count; x < width; x++) output.put((byte) 128);
            }
        } else {
            for (int row = 0; row < height; row++) {
                int rowStart = base + row * rowStride;
                for (int column = 0; column < width; column++) {
                    int index = rowStart + column * pixelStride;
                    output.put(index >= 0 && index < limit ? input.get(index) : (byte) 128);
                }
            }
        }
        while (output.position() < output.capacity()) output.put((byte) 128);
        output.flip();
    }

    /**
     * Camera2 exposes the U and V planes as two views over one interleaved
     * allocation on the devices supported by the reference application.
     * Plane 1 starts at U, so copying its rows directly produces NV12. This is
     * the same fast path used by the reference APK and avoids a million
     * per-pixel Java reads for every 1080p frame.
     */
    private static void copyNv12(Image.Plane uPlane, int cropX, int cropY,
                                 int rowBytes, int height, ByteBuffer output) {
        ByteBuffer input = uPlane.getBuffer().duplicate();
        int rowStride = uPlane.getRowStride();
        int pixelStride = uPlane.getPixelStride();
        int base = input.position() + cropY * rowStride + cropX * pixelStride;
        int limit = input.limit();
        output.clear();
        for (int row = 0; row < height; row++) {
            int start = base + row * rowStride;
            int count = Math.min(rowBytes, Math.max(0, limit - start));
            if (count > 0) {
                input.position(start);
                input.limit(start + count);
                output.put(input);
                input.limit(limit);
            }
            for (int index = count; index < rowBytes; index++) {
                output.put((byte) 128);
            }
        }
        output.flip();
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("YUV shader compile failed: " + log);
        }
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer
                .allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }
}
