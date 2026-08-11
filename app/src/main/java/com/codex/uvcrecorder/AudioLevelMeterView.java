package com.codex.uvcrecorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

public final class AudioLevelMeterView extends View {
    private static final int SEGMENTS = 24;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float level;
    private float db = -90f;
    private int panelAlpha = Math.round(255f * 0.65f);
    private boolean available;

    public AudioLevelMeterView(Context context) {
        super(context);
    }

    public AudioLevelMeterView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setPanelOpacity(int percent) {
        panelAlpha = Math.round(255f * Math.max(20, Math.min(100, percent)) / 100f);
        invalidate();
    }

    void setLevel(float value, float decibels) {
        available = true;
        float next = Math.max(0f, Math.min(1f, value));
        level = next >= level ? next : Math.max(next, level * 0.84f);
        db = decibels;
        invalidate();
    }

    void setUnavailable() {
        available = false;
        level = 0f;
        db = -90f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float radius = 12f * density;
        rect.set(0, 0, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(panelAlpha, 44, 48, 58));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(Color.argb(Math.max(70, panelAlpha), 255, 255, 255));
        canvas.drawRoundRect(rect.left + density / 2f, rect.top + density / 2f,
                rect.right - density / 2f, rect.bottom - density / 2f, radius, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(11f * density);
        paint.setColor(Color.WHITE);
        canvas.drawText("音频", getWidth() / 2f, 18f * density, paint);

        float left = 15f * density;
        float right = getWidth() - 15f * density;
        float top = 27f * density;
        float bottom = getHeight() - 31f * density;
        float gap = 2f * density;
        float segmentHeight = (bottom - top - gap * (SEGMENTS - 1)) / SEGMENTS;
        int active = Math.round(level * SEGMENTS);
        for (int index = 0; index < SEGMENTS; index++) {
            float segmentBottom = bottom - index * (segmentHeight + gap);
            float segmentTop = segmentBottom - segmentHeight;
            if (index < active) {
                float fraction = index / (float) (SEGMENTS - 1);
                if (fraction >= 0.82f) paint.setColor(Color.rgb(239, 83, 80));
                else if (fraction >= 0.62f) paint.setColor(Color.rgb(255, 202, 40));
                else paint.setColor(Color.rgb(102, 187, 106));
            } else {
                paint.setColor(Color.argb(Math.max(45, panelAlpha / 2), 184, 188, 200));
            }
            canvas.drawRoundRect(left, segmentTop, right, segmentBottom,
                    2f * density, 2f * density, paint);
        }

        paint.setTextSize(9.5f * density);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(available ? Color.WHITE : Color.rgb(184, 188, 200));
        String value = available && db > -89f
                ? String.format(Locale.getDefault(), "%.0f dB", db) : "-- dB";
        canvas.drawText(value, getWidth() / 2f, getHeight() - 10f * density, paint);
    }
}
