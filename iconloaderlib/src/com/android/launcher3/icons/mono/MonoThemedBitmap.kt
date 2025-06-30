/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.launcher3.icons.mono

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Shader.TileMode.CLAMP
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.ClockDrawableWrapper.ClockAnimationInfo
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.icons.IconShape
import com.android.launcher3.icons.ThemedBitmap
import java.nio.ByteBuffer

class MonoThemedBitmap(
    val mono: Bitmap,
    private val colorProvider: (Context) -> IntArray = ThemedIconDelegate.Companion::getColors,
) : ThemedBitmap {

    override fun newDrawable(
        info: BitmapInfo,
        context: Context,
        shape: IconShape,
    ): FastBitmapDrawable {
        val colors = colorProvider(context)
        return FastBitmapDrawable(info, shape, ThemedIconInfo(mono, colors[0], colors[1]))
    }

    override fun serialize() =
        ByteArray(mono.width * mono.height).apply { mono.copyPixelsToBuffer(ByteBuffer.wrap(this)) }
}

class ClockThemedBitmap(
    private val animInfo: ClockAnimationInfo,
    private val colorProvider: (Context) -> IntArray = ThemedIconDelegate.Companion::getColors,
) : ThemedBitmap {

    override fun newDrawable(
        info: BitmapInfo,
        context: Context,
        shape: IconShape,
    ): FastBitmapDrawable {
        val colors = colorProvider(context)
        return FastBitmapDrawable(
            info,
            shape,
            animInfo.copy(
                themeFgColor = colors[1],
                shaderProvider = { LinearGradient(0f, 0f, 1f, 1f, colors[0], colors[0], CLAMP) },
            ),
        )
    }

    override fun serialize() = byteArrayOf()
}
