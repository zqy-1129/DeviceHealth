/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.devicehealthcenter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooth sparkline used by the overview trend cards.
 *
 * <p>The view draws its own grid, gradient area, smoothed line and end marker. Labels and
 * values are rendered by the surrounding layout so that typography stays consistent with the
 * rest of the screen.
 */
public final class TrendView extends View {
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAreaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEmptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLinePath = new Path();
    private final Path mAreaPath = new Path();
    private final List<Float> mPoints = new ArrayList<>();

    private final float mDensity;
    private final int mSurfaceColor;
    private final String mEmptyText;

    private int mColor = 0xFF0D9488;

    public TrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mSurfaceColor = context.getColor(R.color.dh_surface);
        mEmptyText = context.getString(R.string.trend_empty);

        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(dp(1));
        mGridPaint.setColor(context.getColor(R.color.dh_chart_grid));
        mGridPaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(4)}, 0f));

        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(dp(2.4f));
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);

        mAreaPaint.setStyle(Paint.Style.FILL);

        mDotPaint.setStyle(Paint.Style.FILL);
        mDotStrokePaint.setStyle(Paint.Style.STROKE);
        mDotStrokePaint.setStrokeWidth(dp(1.6f));
        mDotStrokePaint.setColor(mSurfaceColor);

        mEmptyPaint.setTextAlign(Paint.Align.CENTER);
        mEmptyPaint.setTextSize(dp(10));
        mEmptyPaint.setColor(context.getColor(R.color.dh_muted));
    }

    /**
     * Replaces the displayed series. Non-finite samples are ignored.
     */
    public void setSeries(List<Float> points, int color) {
        mPoints.clear();
        if (points != null) {
            for (Float point : points) {
                if (point != null && Float.isFinite(point)) {
                    mPoints.add(point);
                }
            }
        }
        mColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width = getWidth();
        final float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }

        final float left = dp(3);
        final float right = width - dp(3);
        final float top = dp(6);
        final float bottom = height - dp(8);
        final float chartHeight = bottom - top;
        if (chartHeight <= 0f || right <= left) {
            return;
        }

        drawGrid(canvas, left, right, top, chartHeight);

        if (mPoints.isEmpty()) {
            mEmptyPaint.setTextSize(dp(10));
            canvas.drawText(mEmptyText, width / 2f, top + chartHeight / 2f, mEmptyPaint);
            return;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float value : mPoints) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (max - min < 0.5f) {
            min -= 0.5f;
            max += 0.5f;
        }
        final float padding = (max - min) * 0.12f;
        min -= padding;
        max += padding;

        final int count = mPoints.size();
        final float step = count > 1 ? (right - left) / (count - 1f) : 0f;

        mLinePath.reset();
        mAreaPath.reset();
        float lastX = left;
        float lastY = bottom;
        for (int i = 0; i < count; i++) {
            final float x = count == 1 ? (left + right) / 2f : left + step * i;
            final float y = bottom - (mPoints.get(i) - min) / (max - min) * chartHeight;
            if (i == 0) {
                mLinePath.moveTo(x, y);
                mAreaPath.moveTo(x, bottom);
                mAreaPath.lineTo(x, y);
            } else {
                final float controlX = lastX + (x - lastX) / 2f;
                mLinePath.cubicTo(controlX, lastY, controlX, y, x, y);
                mAreaPath.cubicTo(controlX, lastY, controlX, y, x, y);
            }
            lastX = x;
            lastY = y;
        }

        mAreaPath.lineTo(lastX, bottom);
        mAreaPath.close();

        mAreaPaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                withAlpha(mColor, 52), withAlpha(mColor, 0), Shader.TileMode.CLAMP));
        canvas.drawPath(mAreaPath, mAreaPaint);
        mAreaPaint.setShader(null);

        mLinePaint.setColor(withAlpha(mColor, 46));
        mLinePaint.setStrokeWidth(dp(6));
        canvas.drawPath(mLinePath, mLinePaint);

        mLinePaint.setColor(mColor);
        mLinePaint.setStrokeWidth(dp(2.4f));
        canvas.drawPath(mLinePath, mLinePaint);

        mDotPaint.setColor(withAlpha(mColor, 56));
        canvas.drawCircle(lastX, lastY, dp(5.5f), mDotPaint);
        mDotPaint.setColor(mColor);
        canvas.drawCircle(lastX, lastY, dp(3f), mDotPaint);
        canvas.drawCircle(lastX, lastY, dp(3f), mDotStrokePaint);
    }

    private void drawGrid(Canvas canvas, float left, float right, float top, float chartHeight) {
        for (int i = 1; i <= 3; i++) {
            final float y = top + chartHeight * i / 4f;
            canvas.drawLine(left, y, right, y, mGridPaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * mDensity;
    }
}
