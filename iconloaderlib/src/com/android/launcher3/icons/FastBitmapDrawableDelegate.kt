/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.icons

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.ColorUtils

/** A delegate for changing the rendering of [FastBitmapDrawable], to support multi-inheritance */
interface FastBitmapDrawableDelegate {

    /** [android.graphics.drawable.Drawable.onBoundsChange] */
    fun onBoundsChange(bounds: Rect) {}

    /** [android.graphics.drawable.Drawable.draw] */
    fun drawContent(info: BitmapInfo, canvas: Canvas, bounds: Rect, paint: Paint) {
        canvas.drawBitmap(info.icon, null, bounds, paint)
    }

    /** [FastBitmapDrawable.getIconColor] */
    fun getIconColor(info: BitmapInfo): Int =
        ColorUtils.compositeColors(
            GraphicsUtils.setColorAlphaBound(Color.WHITE, FastBitmapDrawable.WHITE_SCRIM_ALPHA),
            info.color,
        )

    /** [FastBitmapDrawable.isThemed] */
    fun isThemed() = false

    /** [android.graphics.drawable.Drawable.setAlpha] */
    fun setAlpha(alpha: Int) {}

    /** [android.graphics.drawable.Drawable.setColorFilter] */
    fun updateFilter(isDisabled: Boolean, disabledAlpha: Float) {}

    /** [android.graphics.drawable.Drawable.setVisible] */
    fun onVisibilityChanged(isVisible: Boolean) {}

    /** [android.graphics.drawable.Drawable.onLevelChange] */
    fun onLevelChange(level: Int): Boolean = false

    /**
     * Interface for creating new delegates. This should not store any state information and can
     * safely be stored in a [android.graphics.drawable.Drawable.ConstantState]
     */
    fun interface DelegateFactory {

        fun newDelegate(
            bitmapInfo: BitmapInfo,
            paint: Paint,
            host: FastBitmapDrawable,
        ): FastBitmapDrawableDelegate
    }

    object SimpleDelegateFactory : DelegateFactory {
        override fun newDelegate(bitmapInfo: BitmapInfo, paint: Paint, host: FastBitmapDrawable) =
            object : FastBitmapDrawableDelegate {}
    }
}
