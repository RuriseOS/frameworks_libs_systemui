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
import android.content.pm.PackageManager
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
import android.os.Bundle
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
import java.util.function.IntFunction
import kotlin.math.max

/**
 * Wrapper over [AdaptiveIconDrawable] to intercept icon flattening logic for dynamic clock icons
 */
class ClockDrawableWrapper private constructor(base: AdaptiveIconDrawable) :
    AdaptiveIconDrawable(base.background, base.foreground), Extender {
    private val mAnimationInfo = AnimationInfo()
    private var mThemeInfo: AnimationInfo? = null

    override fun getMonochrome(): Drawable? {
        val info = mThemeInfo ?: return null
        val d = info.baseDrawableState?.newDrawable()?.mutate()
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
            mAnimationInfo,
            flattenBG,
            themeInfo,
            themeBG,
        )
    }

    override fun drawForPersistence(canvas: Canvas) {
        val foreground = foreground as LayerDrawable
        resetLevel(foreground, mAnimationInfo.hourLayerIndex)
        resetLevel(foreground, mAnimationInfo.minuteLayerIndex)
        resetLevel(foreground, mAnimationInfo.secondLayerIndex)
        draw(canvas)
        mAnimationInfo.applyTime(Calendar.getInstance(), getForeground() as LayerDrawable)
    }

    private fun resetLevel(drawable: LayerDrawable, index: Int) {
        if (index != INVALID_VALUE) {
            drawable.getDrawable(index).setLevel(0)
        }
    }

    class AnimationInfo {
        var baseDrawableState: ConstantState? = null

        var hourLayerIndex: Int = 0
        var minuteLayerIndex: Int = 0
        var secondLayerIndex: Int = 0
        var defaultHour: Int = 0
        var defaultMinute: Int = 0
        var defaultSecond: Int = 0

        fun copyForIcon(icon: Drawable): AnimationInfo {
            val result = AnimationInfo()
            result.baseDrawableState = icon.constantState
            result.defaultHour = defaultHour
            result.defaultMinute = defaultMinute
            result.defaultSecond = defaultSecond
            result.hourLayerIndex = hourLayerIndex
            result.minuteLayerIndex = minuteLayerIndex
            result.secondLayerIndex = secondLayerIndex
            return result
        }

        fun applyTime(time: Calendar, foregroundDrawable: LayerDrawable): Boolean {
            time.timeInMillis = System.currentTimeMillis()

            // We need to rotate by the difference from the default time if one is specified.
            val convertedHour = (time[Calendar.HOUR] + (12 - defaultHour)) % 12
            val convertedMinute = (time[Calendar.MINUTE] + (60 - defaultMinute)) % 60
            val convertedSecond = (time[Calendar.SECOND] + (60 - defaultSecond)) % 60

            var invalidate = false
            if (hourLayerIndex != INVALID_VALUE) {
                val hour = foregroundDrawable.getDrawable(hourLayerIndex)
                if (hour.setLevel(convertedHour * 60 + time[Calendar.MINUTE])) {
                    invalidate = true
                }
            }

            if (minuteLayerIndex != INVALID_VALUE) {
                val minute = foregroundDrawable.getDrawable(minuteLayerIndex)
                if (minute.setLevel(time[Calendar.HOUR] * 60 + convertedMinute)) {
                    invalidate = true
                }
            }

            if (secondLayerIndex != INVALID_VALUE) {
                val second = foregroundDrawable.getDrawable(secondLayerIndex)
                if (second.setLevel(convertedSecond * LEVELS_PER_SECOND)) {
                    invalidate = true
                }
            }

            return invalidate
        }
    }

    class ClockBitmapInfo(
        icon: Bitmap,
        color: Int,
        scale: Float,
        val animInfo: AnimationInfo,
        val mFlattenedBackground: Bitmap,
        val themeData: AnimationInfo?,
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
            if (
                (creationFlags and FLAG_THEMED) != 0 && themeData != null && themeBackground != null
            ) {
                val colors = getColors(context)
                val tintedDrawable = themeData.baseDrawableState!!.newDrawable().mutate()
                themedFgColor = colors[1]
                tintedDrawable.setTint(colors[1])
                bg = themeBackground
                bgFilter = BlendModeColorFilter(colors[0], SRC_IN)
            } else {
                themedFgColor = NO_COLOR
                bg = mFlattenedBackground
                bgFilter = null
            }
            val delegateInfo =
                ClockDelegateInfo(themedFgColor, boundsOffset, animInfo, bg, bgFilter)
            val d = FastBitmapDrawable(this, iconShape ?: defaultIconShape, delegateInfo)
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

    @JvmRecord
    private data class ClockDelegateInfo(
        val themeFgColor: Int,
        val boundsOffset: Float,
        val animInfo: AnimationInfo,
        val bg: Bitmap,
        val bgFilter: ColorFilter?,
    ) : DelegateFactory {
        override fun newDelegate(
            bitmapInfo: BitmapInfo,
            iconShape: IconShape,
            paint: Paint,
            host: FastBitmapDrawable,
        ): FastBitmapDrawableDelegate {
            return ClockDrawableDelegate(this, host)
        }
    }

    private class ClockDrawableDelegate(
        cs: ClockDelegateInfo,
        private val mHost: FastBitmapDrawable,
    ) : FastBitmapDrawableDelegate, Runnable {
        private val mTime: Calendar = Calendar.getInstance()

        private val mBoundsOffset = cs.boundsOffset
        private val mAnimInfo: AnimationInfo?

        private val mBG: Bitmap
        private val mBgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val mBgFilter: ColorFilter?
        private val mThemedFgColor: Int

        private val mFullDrawable: AdaptiveIconDrawable
        private val mFG: LayerDrawable
        private val mCanvasScale: Float

        init {
            mAnimInfo = cs.animInfo

            mBG = cs.bg
            mBgFilter = cs.bgFilter
            mBgPaint.setColorFilter(cs.bgFilter)
            mThemedFgColor = cs.themeFgColor

            mFullDrawable =
                mAnimInfo.baseDrawableState!!.newDrawable().mutate() as AdaptiveIconDrawable
            mFG = mFullDrawable.foreground as LayerDrawable

            // Time needs to be applied here since drawInternal is NOT guaranteed to be called
            // before this foreground drawable is shown on the screen.
            mAnimInfo.applyTime(mTime, mFG)
            mCanvasScale = 1 - 2 * mBoundsOffset
        }

        override fun setAlpha(alpha: Int) {
            mBgPaint.alpha = alpha
            mFG.alpha = alpha
        }

        override fun onBoundsChange(bounds: Rect) {
            // b/211896569 AdaptiveIcon does not work properly when bounds
            // are not aligned to top/left corner
            mFullDrawable.setBounds(0, 0, bounds.width(), bounds.height())
        }

        override fun drawContent(
            info: BitmapInfo,
            host: FastBitmapDrawable,
            canvas: Canvas,
            bounds: Rect,
            paint: Paint,
        ) {
            if (mAnimInfo == null) {
                super.drawContent(info, mHost, canvas, bounds, paint)
                return
            }
            canvas.drawBitmap(mBG, null, bounds, mBgPaint)

            // prepare and draw the foreground
            mAnimInfo.applyTime(mTime, mFG)
            canvas.transformed {
                translate(bounds.left.toFloat(), bounds.top.toFloat())
                scale(
                    mCanvasScale,
                    mCanvasScale,
                    (bounds.width() / 2).toFloat(),
                    (bounds.height() / 2).toFloat(),
                )
                clipPath(mFullDrawable.iconMask)
                mFG.draw(this)
            }
            reschedule()
        }

        override fun isThemed(): Boolean {
            return mBgPaint.colorFilter != null
        }

        override fun updateFilter(isDisabled: Boolean, disabledAlpha: Float) {
            val alpha =
                if (isDisabled) (disabledAlpha * FastBitmapDrawable.FULLY_OPAQUE).toInt()
                else FastBitmapDrawable.FULLY_OPAQUE
            setAlpha(alpha)
            mBgPaint.setColorFilter(if (isDisabled) getDisabledColorFilter() else mBgFilter)
            mFG.colorFilter = if (isDisabled) getDisabledColorFilter() else null
        }

        override fun getIconColor(info: BitmapInfo): Int {
            return if (isThemed()) mThemedFgColor else super.getIconColor(info)
        }

        override fun run() {
            if (mAnimInfo!!.applyTime(mTime, mFG)) {
                mHost.invalidateSelf()
            } else {
                reschedule()
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            if (isVisible) {
                reschedule()
            } else {
                mHost.unscheduleSelf(this)
            }
        }

        fun reschedule() {
            if (!mHost.isVisible) {
                return
            }
            mHost.unscheduleSelf(this)
            val upTime = SystemClock.uptimeMillis()
            val step = TICK_MS /* tick every 200 ms */
            mHost.scheduleSelf(this, upTime - ((upTime % step)) + step)
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
                val pm = context.packageManager
                val appInfo =
                    pm.getApplicationInfo(
                        pkg,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_META_DATA,
                    )
                val res = pm.getResourcesForApplication(appInfo)
                return forExtras(appInfo.metaData) { resId: Int ->
                    res.getDrawableForDensity(resId, iconDpi)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Unable to load clock drawable info", e)
            }
            return null
        }

        private fun forExtras(
            metadata: Bundle?,
            drawableProvider: IntFunction<Drawable>,
        ): ClockDrawableWrapper? {
            if (metadata == null) {
                return null
            }
            val drawableId = metadata.getInt(ROUND_ICON_METADATA_KEY, 0)
            if (drawableId == 0) {
                return null
            }

            val drawable =
                drawableProvider.apply(drawableId).mutate() as? AdaptiveIconDrawable ?: return null

            val wrapper = ClockDrawableWrapper(drawable)
            wrapper.mAnimationInfo.apply {
                baseDrawableState = drawable.constantState
                hourLayerIndex = metadata.getInt(HOUR_INDEX_METADATA_KEY, INVALID_VALUE)
                minuteLayerIndex = metadata.getInt(MINUTE_INDEX_METADATA_KEY, INVALID_VALUE)
                secondLayerIndex = metadata.getInt(SECOND_INDEX_METADATA_KEY, INVALID_VALUE)

                defaultHour = metadata.getInt(DEFAULT_HOUR_METADATA_KEY, 0)
                defaultMinute = metadata.getInt(DEFAULT_MINUTE_METADATA_KEY, 0)
                defaultSecond = metadata.getInt(DEFAULT_SECOND_METADATA_KEY, 0)

                val foreground = wrapper.foreground as LayerDrawable
                val layerCount = foreground.numberOfLayers
                if (hourLayerIndex < 0 || hourLayerIndex >= layerCount) {
                    hourLayerIndex = INVALID_VALUE
                }
                if (minuteLayerIndex < 0 || minuteLayerIndex >= layerCount) {
                    minuteLayerIndex = INVALID_VALUE
                }
                if (secondLayerIndex < 0 || secondLayerIndex >= layerCount) {
                    secondLayerIndex = INVALID_VALUE
                } else if (DISABLE_SECONDS) {
                    foreground.setDrawable(secondLayerIndex, null)
                    secondLayerIndex = INVALID_VALUE
                }

                if (IconProvider.ATLEAST_T && drawable.monochrome is LayerDrawable) {
                    wrapper.mThemeInfo =
                        copyForIcon(
                            AdaptiveIconDrawable(
                                ColorDrawable(Color.WHITE),
                                drawable.monochrome!!.mutate(),
                            )
                        )
                }
                applyTime(Calendar.getInstance(), foreground)
            }

            return wrapper
        }
    }
}
