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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Circular gauge used by the overview hero card.
 *
 * <p>The gauge draws a background track, a gradient arc for the current value and a compact
 * state label in the center. All colors are resolved from theme resources, so switching
 * between light and dark mode only requires a configuration change.
 */
public final class GaugeView extends View {
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCaptionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOval = new RectF();

    private final int mStartColor;
    private final int mEndColor;
    private final String mCaption;
    private final float mStrokeWidth;

    private Shader mShader;
    private float mValue;
    private float mMax = 100f;
    private String mState = "—";
    private int mStateColor;

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStartColor = context.getColor(R.color.dh_gauge_start);
        mEndColor = context.getColor(R.color.dh_gauge_end);
        mCaption = context.getString(R.string.overall_state);
        mStateColor = context.getColor(R.color.dh_muted);
        mStrokeWidth = dp(8);

        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeWidth(mStrokeWidth);
        mTrackPaint.setColor(context.getColor(R.color.dh_gauge_track));

        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setStrokeWidth(mStrokeWidth);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);

        mInnerPaint.setStyle(Paint.Style.FILL);
        mInnerPaint.setColor(context.getColor(R.color.dh_gauge_inner));

        Typeface medium = Typeface.create("sans-serif-medium", Typeface.BOLD);
        mStatePaint.setTextAlign(Paint.Align.CENTER);
        mStatePaint.setTypeface(medium);
        mStatePaint.setTextSize(sp(12));
        mStatePaint.setColor(mStateColor);

        mCaptionPaint.setTextAlign(Paint.Align.CENTER);
        mCaptionPaint.setTextSize(sp(8));
        mCaptionPaint.setColor(context.getColor(R.color.dh_muted));
    }

    public void setValue(float value, float max) {
        mValue = Math.max(0f, value);
        mMax = max > 0f ? max : 100f;
        invalidate();
    }

    public void setState(String state, int color) {
        mState = state != null && !state.isEmpty() ? state : "—";
        mStateColor = color;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        mShader = new LinearGradient(0f, 0f, width, height,
                mStartColor, mEndColor, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width = getWidth();
        final float height = getHeight();
        final float centerX = width / 2f;
        final float centerY = height / 2f;
        final float radius = Math.min(width, height) / 2f - mStrokeWidth / 2f - dp(4);

        mOval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        canvas.drawArc(mOval, 0f, 360f, false, mTrackPaint);

        final float fraction = mMax <= 0f ? 0f : Math.min(1f, mValue / mMax);
        if (fraction > 0f) {
            mArcPaint.setShader(mShader);
            canvas.drawArc(mOval, -90f, 360f * fraction, false, mArcPaint);
            mArcPaint.setShader(null);
        }

        canvas.drawCircle(centerX, centerY, radius - mStrokeWidth / 2f - dp(5), mInnerPaint);

        mStatePaint.setColor(mStateColor);
        canvas.drawText(mState, centerX, centerY + dp(4), mStatePaint);
        canvas.drawText(mCaption, centerX, centerY + dp(16), mCaptionPaint);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
