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

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Shader.TileMode.CLAMP
import androidx.core.graphics.ColorUtils
import com.android.launcher3.icons.BitmapInfo.Companion.FLAG_FULL_BLEED

/** A delegate for changing the rendering of [FastBitmapDrawable], to support multi-inheritance */
interface FastBitmapDrawableDelegate {

    /** [android.graphics.drawable.Drawable.onBoundsChange] */
    fun onBoundsChange(bounds: Rect) {}

    /** [android.graphics.drawable.Drawable.draw] */
    fun drawContent(
        info: BitmapInfo,
        host: FastBitmapDrawable,
        canvas: Canvas,
        bounds: Rect,
        paint: Paint,
    ) {
        if ((info.flags and FLAG_FULL_BLEED) != 0) {
            host.drawShaderInBounds(canvas, bounds)
        } else {
            canvas.drawBitmap(info.icon, null, bounds, paint)
        }
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
    fun updateFilter(filter: ColorFilter?) {}

    /** [android.graphics.drawable.Drawable.setVisible] */
    fun onVisibilityChanged(isVisible: Boolean) {}

    /** [android.graphics.drawable.Drawable.onLevelChange] */
    fun onLevelChange(level: Int): Boolean = false

    /** Creates a default shader to be used for drawing the drawable */
    fun createPaintShader(bitmapInfo: BitmapInfo, shape: IconShape): Shader? {
        return if ((bitmapInfo.flags and FLAG_FULL_BLEED) != 0) {
            BitmapShader(bitmapInfo.icon, CLAMP, CLAMP)
        } else {
            null
        }
    }

    /**
     * Interface for creating new delegates. This should not store any state information and can
     * safely be stored in a [android.graphics.drawable.Drawable.ConstantState]
     */
    fun interface DelegateFactory {

        fun newDelegate(
            bitmapInfo: BitmapInfo,
            iconShape: IconShape,
            paint: Paint,
            host: FastBitmapDrawable,
        ): FastBitmapDrawableDelegate
    }

    object SimpleDelegateFactory : DelegateFactory {
        override fun newDelegate(
            bitmapInfo: BitmapInfo,
            iconShape: IconShape,
            paint: Paint,
            host: FastBitmapDrawable,
        ) = object : FastBitmapDrawableDelegate {}
    }
}
