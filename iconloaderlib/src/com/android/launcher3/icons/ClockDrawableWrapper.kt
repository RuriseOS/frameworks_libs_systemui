/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager.GET_META_DATA
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.graphics.Bitmap
import android.graphics.BlendMode.SRC_IN
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build.VERSION_CODES
import android.os.SystemClock
import android.util.Log
import com.android.launcher3.icons.BitmapInfo.Extender
import com.android.launcher3.icons.FastBitmapDrawable.Companion.getDisabledColorFilter
import com.android.launcher3.icons.FastBitmapDrawableDelegate.DelegateFactory
import com.android.launcher3.icons.GraphicsUtils.transformed
import com.android.launcher3.icons.cache.CacheLookupFlag
import com.android.launcher3.icons.mono.ThemedIconDelegate.Companion.getColors
import java.util.Calendar
import java.util.concurrent.TimeUnit.MINUTES
import kotlin.math.max

/**
 * Wrapper over [AdaptiveIconDrawable] to intercept icon flattening logic for dynamic clock icons
 */
class ClockDrawableWrapper
private constructor(base: AdaptiveIconDrawable, private val animationInfo: ClockAnimationInfo) :
    AdaptiveIconDrawable(base.background, base.foreground), Extender {
    private var mThemeInfo: ClockAnimationInfo? = null

    override fun getMonochrome(): Drawable? {
        val info = mThemeInfo ?: return null
        val d = info.baseDrawableState.newDrawable().mutate()
        if (d is AdaptiveIconDrawable) {
            val mono = d.foreground
            info.applyTime(Calendar.getInstance(), mono as LayerDrawable)
            return mono
        }
        return null
    }

    override fun getExtendedInfo(
        bitmap: Bitmap,
        color: Int,
        iconFactory: BaseIconFactory,
        normalizationScale: Float,
    ): ClockBitmapInfo {
        val flattenBG =
            iconFactory.createScaledBitmap(
                AdaptiveIconDrawable(background.constantState!!.newDrawable(), null),
                BaseIconFactory.MODE_HARDWARE_WITH_SHADOW,
            )

        // Only pass theme info if mono-icon is enabled
        val themeInfo = if (iconFactory.themeController != null) mThemeInfo else null
        val themeBG = if (themeInfo == null) null else iconFactory.whiteShadowLayer

        return ClockBitmapInfo(
            bitmap,
            color,
            normalizationScale,
            animationInfo,
            flattenBG,
            themeInfo,
            themeBG,
        )
    }

    override fun drawForPersistence(canvas: Canvas) {
        val foreground = foreground as LayerDrawable
        resetLevel(foreground, animationInfo.hourLayerIndex)
        resetLevel(foreground, animationInfo.minuteLayerIndex)
        resetLevel(foreground, animationInfo.secondLayerIndex)
        draw(canvas)
        animationInfo.applyTime(Calendar.getInstance(), getForeground() as LayerDrawable)
    }

    private fun resetLevel(drawable: LayerDrawable, index: Int) {
        if (index != INVALID_VALUE) {
            drawable.getDrawable(index).setLevel(0)
        }
    }

    data class ClockAnimationInfo(
        val hourLayerIndex: Int,
        val minuteLayerIndex: Int,
        val secondLayerIndex: Int,
        val defaultHour: Int,
        val defaultMinute: Int,
        val defaultSecond: Int,
        val baseDrawableState: ConstantState,
        val themeFgColor: Int = NO_COLOR,
        val boundsOffset: Float = 0f,
        val bg: Bitmap = BitmapInfo.LOW_RES_ICON,
        val bgFilter: ColorFilter? = null,
    ) : DelegateFactory {

        fun applyTime(time: Calendar, foregroundDrawable: LayerDrawable): Boolean {
            time.timeInMillis = System.currentTimeMillis()

            // We need to rotate by the difference from the default time if one is specified.
            val invalidateHour =
                foregroundDrawable.applyLevel(hourLayerIndex) {
                    val convertedHour = (time[Calendar.HOUR] + (12 - defaultHour)) % 12
                    convertedHour * 60 + time[Calendar.MINUTE]
                }
            val invalidateMinute =
                foregroundDrawable.applyLevel(minuteLayerIndex) {
                    val convertedMinute = (time[Calendar.MINUTE] + (60 - defaultMinute)) % 60
                    time[Calendar.HOUR] * 60 + convertedMinute
                }
            val invalidateSecond =
                foregroundDrawable.applyLevel(secondLayerIndex) {
                    val convertedSecond = (time[Calendar.SECOND] + (60 - defaultSecond)) % 60
                    convertedSecond * LEVELS_PER_SECOND
                }

            return invalidateHour || invalidateMinute || invalidateSecond
        }

        override fun newDelegate(
            bitmapInfo: BitmapInfo,
            iconShape: IconShape,
            paint: Paint,
            host: FastBitmapDrawable,
        ): FastBitmapDrawableDelegate = ClockDrawableDelegate(this, host)
    }

    class ClockBitmapInfo(
        icon: Bitmap,
        color: Int,
        scale: Float,
        val animInfo: ClockAnimationInfo,
        val mFlattenedBackground: Bitmap,
        val themeData: ClockAnimationInfo?,
        val themeBackground: Bitmap?,
    ) : BitmapInfo(icon, color, flags = 0, themedBitmap = null) {
        val boundsOffset: Float =
            max(ShadowGenerator.BLUR_FACTOR.toDouble(), ((1 - scale) / 2).toDouble()).toFloat()

        @TargetApi(VERSION_CODES.TIRAMISU)
        override fun newIcon(
            context: Context,
            @DrawableCreationFlags creationFlags: Int,
            iconShape: IconShape?,
        ): FastBitmapDrawable {
            val bg: Bitmap
            val themedFgColor: Int
            val bgFilter: ColorFilter?
            val baseState: ConstantState
            if (
                (creationFlags and FLAG_THEMED) != 0 && themeData != null && themeBackground != null
            ) {
                val colors = getColors(context)
                val tintedDrawable = themeData.baseDrawableState.newDrawable().mutate()
                themedFgColor = colors[1]
                tintedDrawable.setTint(colors[1])
                bg = themeBackground
                bgFilter = BlendModeColorFilter(colors[0], SRC_IN)
                baseState = tintedDrawable.constantState!!
            } else {
                baseState = animInfo.baseDrawableState
                themedFgColor = NO_COLOR
                bg = mFlattenedBackground
                bgFilter = null
            }

            val animInfoCopy =
                animInfo.copy(
                    baseDrawableState = baseState,
                    themeFgColor = themedFgColor,
                    boundsOffset = boundsOffset,
                    bg = bg,
                    bgFilter = bgFilter,
                )
            val d = FastBitmapDrawable(this, iconShape ?: defaultIconShape, animInfoCopy)
            applyFlags(context, d, creationFlags)
            return d
        }

        override fun canPersist() = false

        override fun clone(): BitmapInfo {
            return copyInternalsTo(
                ClockBitmapInfo(
                    icon,
                    color,
                    1 - 2 * boundsOffset,
                    animInfo,
                    mFlattenedBackground,
                    themeData,
                    themeBackground,
                )
            )
        }

        override val matchingLookupFlag: CacheLookupFlag
            get() = CacheLookupFlag.DEFAULT_LOOKUP_FLAG.withThemeIcon(themeData != null)
    }

    private class ClockDrawableDelegate(
        private val animInfo: ClockAnimationInfo,
        private val host: FastBitmapDrawable,
    ) : FastBitmapDrawableDelegate, Runnable {
        private val time: Calendar = Calendar.getInstance()

        private val boundsOffset = animInfo.boundsOffset
        private val bG: Bitmap = animInfo.bg
        private val bgFilter: ColorFilter? = animInfo.bgFilter
        private val bgPaint =
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = bgFilter
            }
        private val themedFgColor: Int = animInfo.themeFgColor

        private val fullDrawable =
            animInfo.baseDrawableState.newDrawable().mutate() as AdaptiveIconDrawable
        private val foreground = fullDrawable.foreground as LayerDrawable
        private val canvasScale: Float = 1 - 2 * boundsOffset

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            foreground.alpha = alpha
        }

        override fun onBoundsChange(bounds: Rect) {
            // b/211896569 AdaptiveIcon does not work properly when bounds
            // are not aligned to top/left corner
            fullDrawable.setBounds(0, 0, bounds.width(), bounds.height())
        }

        override fun drawContent(
            info: BitmapInfo,
            host: FastBitmapDrawable,
            canvas: Canvas,
            bounds: Rect,
            paint: Paint,
        ) {
            canvas.drawBitmap(bG, null, bounds, bgPaint)

            // prepare and draw the foreground
            animInfo.applyTime(time, foreground)
            canvas.transformed {
                translate(bounds.left.toFloat(), bounds.top.toFloat())
                scale(
                    canvasScale,
                    canvasScale,
                    (bounds.width() / 2).toFloat(),
                    (bounds.height() / 2).toFloat(),
                )
                clipPath(fullDrawable.iconMask)
                foreground.draw(this)
            }
            reschedule()
        }

        override fun isThemed(): Boolean {
            return bgPaint.colorFilter != null
        }

        override fun updateFilter(isDisabled: Boolean, disabledAlpha: Float) {
            val alpha =
                if (isDisabled) (disabledAlpha * FastBitmapDrawable.FULLY_OPAQUE).toInt()
                else FastBitmapDrawable.FULLY_OPAQUE
            setAlpha(alpha)
            bgPaint.setColorFilter(if (isDisabled) getDisabledColorFilter() else bgFilter)
            foreground.colorFilter = if (isDisabled) getDisabledColorFilter() else null
        }

        override fun getIconColor(info: BitmapInfo): Int {
            return if (isThemed()) themedFgColor else super.getIconColor(info)
        }

        override fun run() {
            if (animInfo.applyTime(time, foreground)) {
                host.invalidateSelf()
            } else {
                reschedule()
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            if (isVisible) {
                reschedule()
            } else {
                host.unscheduleSelf(this)
            }
        }

        fun reschedule() {
            if (!host.isVisible) {
                return
            }
            host.unscheduleSelf(this)
            val upTime = SystemClock.uptimeMillis()
            val step = TICK_MS /* tick every 200 ms */
            host.scheduleSelf(this, upTime - ((upTime % step)) + step)
        }
    }

    companion object {
        @JvmField var sRunningInTest: Boolean = false

        private const val TAG = "ClockDrawableWrapper"

        private const val DISABLE_SECONDS = true
        private const val NO_COLOR = -1

        // Time after which the clock icon should check for an update. The actual invalidate
        // will only happen in case of any change.
        val TICK_MS: Long = if (DISABLE_SECONDS) MINUTES.toMillis(1) else 200L

        private const val LAUNCHER_PACKAGE = "com.android.launcher3"
        private const val ROUND_ICON_METADATA_KEY = "$LAUNCHER_PACKAGE.LEVEL_PER_TICK_ICON_ROUND"
        private const val HOUR_INDEX_METADATA_KEY = "$LAUNCHER_PACKAGE.HOUR_LAYER_INDEX"
        private const val MINUTE_INDEX_METADATA_KEY = "$LAUNCHER_PACKAGE.MINUTE_LAYER_INDEX"
        private const val SECOND_INDEX_METADATA_KEY = "$LAUNCHER_PACKAGE.SECOND_LAYER_INDEX"
        private const val DEFAULT_HOUR_METADATA_KEY = "$LAUNCHER_PACKAGE.DEFAULT_HOUR"
        private const val DEFAULT_MINUTE_METADATA_KEY = "$LAUNCHER_PACKAGE.DEFAULT_MINUTE"
        private const val DEFAULT_SECOND_METADATA_KEY = "$LAUNCHER_PACKAGE.DEFAULT_SECOND"

        /* Number of levels to jump per second for the second hand */
        private const val LEVELS_PER_SECOND = 10

        const val INVALID_VALUE: Int = -1

        /**
         * Loads and returns the wrapper from the provided package, or returns null if it is unable
         * to load.
         */
        @JvmStatic
        fun forPackage(context: Context, pkg: String, iconDpi: Int): ClockDrawableWrapper? {
            try {
                return loadClockDrawableUnsafe(context, pkg, iconDpi)
            } catch (e: Exception) {
                Log.d(TAG, "Unable to load clock drawable info", e)
            }
            return null
        }

        private inline fun LayerDrawable.applyLevel(index: Int, level: () -> Int) =
            (index != INVALID_VALUE && getDrawable(index).setLevel(level.invoke()))

        /** Tries to load clock drawable by reading packageManager information */
        @Throws(Exception::class)
        private fun loadClockDrawableUnsafe(
            context: Context,
            pkg: String,
            iconDpi: Int,
        ): ClockDrawableWrapper? {
            val pm = context.packageManager
            val appInfo =
                pm.getApplicationInfo(pkg, MATCH_UNINSTALLED_PACKAGES or GET_META_DATA)
                    ?: return null
            val res = pm.getResourcesForApplication(appInfo)
            val metadata = appInfo.metaData ?: return null
            val drawableId = metadata.getInt(ROUND_ICON_METADATA_KEY, 0)
            val drawable =
                res.getDrawableForDensity(drawableId, iconDpi)?.mutate() as? AdaptiveIconDrawable
                    ?: return null

            val foreground = drawable.foreground as? LayerDrawable ?: return null
            val layerCount = foreground.numberOfLayers

            fun getLayerIndex(key: String) =
                metadata.getInt(key, INVALID_VALUE).let {
                    if (it < 0 || it >= layerCount) INVALID_VALUE else it
                }
            var animInfo =
                ClockAnimationInfo(
                    hourLayerIndex = getLayerIndex(HOUR_INDEX_METADATA_KEY),
                    minuteLayerIndex = getLayerIndex(MINUTE_INDEX_METADATA_KEY),
                    secondLayerIndex = getLayerIndex(SECOND_INDEX_METADATA_KEY),
                    defaultHour = metadata.getInt(DEFAULT_HOUR_METADATA_KEY, 0),
                    defaultMinute = metadata.getInt(DEFAULT_MINUTE_METADATA_KEY, 0),
                    defaultSecond = metadata.getInt(DEFAULT_SECOND_METADATA_KEY, 0),
                    baseDrawableState = drawable.constantState!!,
                )

            if (DISABLE_SECONDS && animInfo.secondLayerIndex != INVALID_VALUE) {
                foreground.setDrawable(animInfo.secondLayerIndex, null)
                animInfo = animInfo.copy(secondLayerIndex = INVALID_VALUE)
            }

            val wrapper = ClockDrawableWrapper(drawable, animInfo)
            if (IconProvider.ATLEAST_T && drawable.monochrome is LayerDrawable) {
                wrapper.mThemeInfo =
                    animInfo.copy(
                        baseDrawableState =
                            AdaptiveIconDrawable(
                                    ColorDrawable(Color.WHITE),
                                    drawable.monochrome!!.mutate(),
                                )
                                .constantState!!
                    )
            }

            animInfo.applyTime(Calendar.getInstance(), foreground)
            return wrapper
        }
    }
}
