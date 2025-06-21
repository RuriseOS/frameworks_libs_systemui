/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.launcher3.icons;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

/**
 * Subclass which draws a placeholder icon when the actual icon is not yet loaded
 */
public class PlaceHolderDrawableDelegate implements FastBitmapDrawableDelegate {

    private final IconShape mIconShape;
    private final Paint mPaint;

    public PlaceHolderDrawableDelegate(BitmapInfo info, IconShape iconShape, Paint paint,
            int loadingColor) {
        mIconShape = iconShape;
        mPaint = paint;
        paint.setColor(ColorUtils.compositeColors(loadingColor, info.color));
    }

    @Override
    public void drawContent(@NonNull BitmapInfo info,
            @NonNull FastBitmapDrawable host,
            @NonNull Canvas canvas, @NonNull Rect bounds, @NonNull Paint paint) {
        int saveCount = canvas.save();
        float pathSize = mIconShape.pathSize;
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / pathSize, bounds.height() / pathSize);
        canvas.drawPath(mIconShape.path, paint);
        canvas.restoreToCount(saveCount);
    }

    /** Updates this placeholder to {@code newIcon} with animation. */
    public void animateIconUpdate(Drawable newIcon) {
        int placeholderColor = mPaint.getColor();
        int originalAlpha = Color.alpha(placeholderColor);

        ValueAnimator iconUpdateAnimation = ValueAnimator.ofInt(originalAlpha, 0);
        iconUpdateAnimation.setDuration(375);
        iconUpdateAnimation.addUpdateListener(valueAnimator -> {
            int newAlpha = (int) valueAnimator.getAnimatedValue();
            int newColor = ColorUtils.setAlphaComponent(placeholderColor, newAlpha);

            newIcon.setColorFilter(new PorterDuffColorFilter(newColor, PorterDuff.Mode.SRC_ATOP));
        });
        iconUpdateAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                newIcon.setColorFilter(null);
            }
        });
        iconUpdateAnimation.start();
    }

    public static class PlaceHolderDelegateFactory implements DelegateFactory {

        private final int mLoadingColor;

        public PlaceHolderDelegateFactory(Context context) {
            mLoadingColor = GraphicsUtils.getAttrColor(context, R.attr.loadingIconColor);
        }

        @NonNull
        @Override
        public FastBitmapDrawableDelegate newDelegate(@NonNull BitmapInfo bitmapInfo,
                @NonNull IconShape iconShape, @NonNull Paint paint,
                    @NonNull FastBitmapDrawable host) {
            return new PlaceHolderDrawableDelegate(bitmapInfo, iconShape, paint, mLoadingColor);
        }
    }
}