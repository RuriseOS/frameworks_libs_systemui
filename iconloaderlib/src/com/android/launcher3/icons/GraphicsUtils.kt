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
package com.android.launcher3.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.PNG
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.util.Log
import androidx.annotation.ColorInt
import java.io.ByteArrayOutputStream
import java.io.IOException

object GraphicsUtils {
    private const val TAG = "GraphicsUtils"

    @JvmField var sOnNewBitmapRunnable: Runnable = Runnable {}

    /**
     * Set the alpha component of `color` to be `alpha`. Unlike the support lib version, it bounds
     * the alpha in valid range instead of throwing an exception to allow for safer interpolation of
     * color animations
     */
    @JvmStatic
    @ColorInt
    fun setColorAlphaBound(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    /** Compresses the bitmap to a byte array for serialization. */
    @JvmStatic
    fun flattenBitmap(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(getExpectedBitmapSize(bitmap))
        try {
            bitmap.compress(PNG, 100, out)
            out.flush()
            out.close()
            return out.toByteArray()
        } catch (e: IOException) {
            Log.w(TAG, "Could not write bitmap")
            return ByteArray(0)
        }
    }

    /**
     * Try go guesstimate how much space the icon will take when serialized to avoid unnecessary
     * allocations/copies during the write (4 bytes per pixel).
     */
    @JvmStatic fun getExpectedBitmapSize(bitmap: Bitmap): Int = bitmap.width * bitmap.height * 4

    @JvmStatic
    fun getArea(r: Region): Int {
        val itr = RegionIterator(r)
        var area = 0
        val tempRect = Rect()
        while (itr.next(tempRect)) {
            area += tempRect.width() * tempRect.height()
        }
        return area
    }

    /** Utility method to track new bitmap creation */
    @JvmStatic fun noteNewBitmapCreated() = sOnNewBitmapRunnable.run()

    /** Returns the color associated with the attribute */
    @JvmStatic
    fun getAttrColor(context: Context, attr: Int): Int =
        context.obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, 0) }

    /** Returns the alpha corresponding to the theme attribute {@param attr} */
    @JvmStatic
    fun getFloat(context: Context, attr: Int, defValue: Float): Float =
        context.obtainStyledAttributes(intArrayOf(attr)).use { it.getFloat(0, defValue) }

    /**
     * Canvas extension function which runs the [block] after preserving the canvas transform using
     * same/restore pair.
     */
    inline fun Canvas.transformed(block: Canvas.() -> Unit) {
        val saveCount = save()
        block.invoke(this)
        restoreToCount(saveCount)
    }

    /** Resizes given IconShape to [newSize] as a new instance of IconShape. */
    @JvmStatic
    fun IconShape.resize(newSize: Float): IconShape {
        val transformedPath = resizePath(path, pathSize, newSize)
        return IconShape(newSize, transformedPath, shadowLayer)
    }

    /** Resizes given [basePath] from [oldSize] to [newSize] as a new instance of Path. */
    @JvmStatic
    fun resizePath(basePath: Path, oldSize: Float, newSize: Float): Path {
        return Path(basePath).apply {
            transform(
                Matrix().apply {
                    setRectToRect(
                        RectF(0f, 0f, oldSize, oldSize),
                        RectF(0f, 0f, newSize, newSize),
                        Matrix.ScaleToFit.CENTER,
                    )
                }
            )
        }
    }
}
