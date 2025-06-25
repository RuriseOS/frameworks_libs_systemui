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

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ALPHA_8
import android.graphics.Bitmap.Config.HARDWARE
import android.graphics.BlendMode.SRC_IN
import android.graphics.BlendModeColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import com.android.launcher3.Flags
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.MODE_ALPHA
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.ClockDrawableWrapper.ClockAnimationInfo
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.MonochromeIconFactory
import com.android.launcher3.icons.SourceHint
import com.android.launcher3.icons.ThemedBitmap
import java.nio.ByteBuffer

@TargetApi(Build.VERSION_CODES.TIRAMISU)
class MonoIconThemeController(
    private val shouldForceThemeIcon: Boolean = false,
    private val colorProvider: (Context) -> IntArray = ThemedIconDelegate.Companion::getColors,
) : IconThemeController {

    override val themeID = "with-theme"

    override fun createThemedBitmap(
        icon: AdaptiveIconDrawable,
        info: BitmapInfo,
        factory: BaseIconFactory,
        sourceHint: SourceHint?,
    ): ThemedBitmap {

        val currentDelegateFactory = info.delegateFactory
        if (currentDelegateFactory is ClockAnimationInfo) {
            val fullDrawable = currentDelegateFactory.baseDrawableState.newDrawable()
            val monoDrawable = (fullDrawable as? AdaptiveIconDrawable)?.monochrome?.mutate()

            if (monoDrawable is LayerDrawable) {
                return ClockThemedBitmap(
                    currentDelegateFactory.copy(
                        baseDrawableState = AdaptiveIconDrawable(null, monoDrawable).constantState!!
                    ),
                    colorProvider,
                )
            } else {
                return ThemedBitmap.NOT_SUPPORTED
            }
        }

        val mono =
            getMonochromeDrawable(
                icon,
                info,
                sourceHint?.isFileDrawable ?: false,
                shouldForceThemeIcon,
            )
        if (mono != null) {
            return MonoThemedBitmap(
                factory.createIconBitmap(mono, 1f /* scale */, MODE_ALPHA, true /* isFullBleed */),
                colorProvider,
            )
        }
        return ThemedBitmap.NOT_SUPPORTED
    }

    /**
     * Returns a monochromatic version of the given drawable or null, if it is not supported
     *
     * @param base the original icon
     */
    private fun getMonochromeDrawable(
        base: AdaptiveIconDrawable,
        info: BitmapInfo,
        isFileDrawable: Boolean,
        shouldForceThemeIcon: Boolean,
    ): Drawable? {
        val mono = base.monochrome
        if (mono != null) {
            return InsetDrawable(mono, -AdaptiveIconDrawable.getExtraInsetFraction())
        }
        if (Flags.forceMonochromeAppIcons() && shouldForceThemeIcon && !isFileDrawable) {
            return MonochromeIconFactory(info.icon.width).wrap(base)
        }
        return null
    }

    override fun decode(
        bytes: ByteArray,
        info: BitmapInfo,
        factory: BaseIconFactory,
        sourceHint: SourceHint,
    ): ThemedBitmap {
        val icon = info.icon
        if (bytes.size != icon.height * icon.width) return ThemedBitmap.NOT_SUPPORTED

        var monoBitmap = Bitmap.createBitmap(icon.width, icon.height, ALPHA_8)
        monoBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

        val hwMonoBitmap = monoBitmap.copy(HARDWARE, false /*isMutable*/)
        if (hwMonoBitmap != null) {
            monoBitmap.recycle()
            monoBitmap = hwMonoBitmap
        }
        return MonoThemedBitmap(monoBitmap, colorProvider)
    }

    override fun createThemedAdaptiveIcon(
        context: Context,
        originalIcon: AdaptiveIconDrawable,
        info: BitmapInfo?,
    ): AdaptiveIconDrawable {
        val colors = colorProvider(context)
        originalIcon.mutate()
        var monoDrawable = originalIcon.monochrome?.apply { setTint(colors[1]) }

        if (monoDrawable == null) {
            info?.themedBitmap?.let { themedBitmap ->
                if (themedBitmap is MonoThemedBitmap) {
                    // Inject a previously generated monochrome icon
                    // Use BitmapDrawable instead of FastBitmapDrawable so that the colorState is
                    // preserved in constantState
                    // Inset the drawable according to the AdaptiveIconDrawable layers
                    monoDrawable =
                        InsetDrawable(
                            BitmapDrawable(themedBitmap.mono).apply {
                                colorFilter = BlendModeColorFilter(colors[1], SRC_IN)
                            },
                            AdaptiveIconDrawable.getExtraInsetFraction() / 2,
                        )
                }
            }
        }

        return monoDrawable?.let { AdaptiveIconDrawable(ColorDrawable(colors[0]), it) }
            ?: originalIcon
    }
}
