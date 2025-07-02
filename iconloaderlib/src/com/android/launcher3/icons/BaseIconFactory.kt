/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.Intent.ShortcutIconResource
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ALPHA_8
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.UserHandle
import android.util.SparseArray
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import com.android.launcher3.Flags
import com.android.launcher3.icons.BitmapInfo.Companion.of
import com.android.launcher3.icons.BitmapInfo.Extender
import com.android.launcher3.icons.ColorExtractor.findDominantColorByHue
import com.android.launcher3.icons.GraphicsUtils.generateIconShape
import com.android.launcher3.icons.GraphicsUtils.transformed
import com.android.launcher3.icons.ShadowGenerator.BLUR_FACTOR
import com.android.launcher3.util.FlagOp
import com.android.launcher3.util.UserIconInfo
import com.android.launcher3.util.UserIconInfo.TYPE_MAIN
import com.android.launcher3.util.UserIconInfo.TYPE_WORK
import com.android.systemui.shared.Flags.extendibleThemeManager
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.math.ceil
import kotlin.math.max

/**
 * This class will be moved to androidx library. There shouldn't be any dependency outside this
 * package.
 */
open class BaseIconFactory
@JvmOverloads
constructor(
    @JvmField val context: Context,
    @JvmField val fullResIconDpi: Int,
    @JvmField val iconBitmapSize: Int,
    private val drawFullBleedIcons: Boolean = false,
    val themeController: IconThemeController? = null,
) : AutoCloseable {

    @Retention(SOURCE)
    @IntDef(MODE_DEFAULT, MODE_ALPHA, MODE_WITH_SHADOW, MODE_HARDWARE_WITH_SHADOW, MODE_HARDWARE)
    internal annotation class BitmapGenerationMode

    private val mOldBounds = Rect()

    private val cachedUserInfo = SparseArray<UserIconInfo>()

    protected val mContext: Context = context.applicationContext

    private val mCanvas =
        Canvas().apply {
            drawFilter = PaintFlagsDrawFilter(Paint.DITHER_FLAG, Paint.FILTER_BITMAP_FLAG)
        }

    private val shadowGenerator: ShadowGenerator by lazy { ShadowGenerator(iconBitmapSize) }

    /** Default IconShape for when custom shape is not needed */
    val defaultIconShape: IconShape by lazy {
        if (!drawFullBleedIcons) IconShape.EMPTY
        else
            generateIconShape(
                iconBitmapSize,
                AdaptiveIconDrawable(ColorDrawable(Color.BLACK), null)
                    .apply { setBounds(0, 0, iconBitmapSize, iconBitmapSize) }
                    .iconMask,
            )
    }

    @Suppress("deprecation")
    fun createIconBitmap(iconRes: ShortcutIconResource): BitmapInfo? {
        try {
            val resources = mContext.packageManager.getResourcesForApplication(iconRes.packageName)
            if (resources != null) {
                val id = resources.getIdentifier(iconRes.resourceName, null, null)
                // do not stamp old legacy shortcuts as the app may have already forgotten about it
                return createBadgedIconBitmap(resources.getDrawableForDensity(id, fullResIconDpi)!!)
            }
        } catch (e: Exception) {
            // Icon not found.
        }
        return null
    }

    /**
     * Create a placeholder icon using the passed in text.
     *
     * @param placeholder used for foreground element in the icon bitmap
     * @param color used for the foreground text color
     */
    fun createIconBitmap(placeholder: String, color: Int): BitmapInfo {
        val drawable =
            AdaptiveIconDrawable(
                ColorDrawable(PLACEHOLDER_BACKGROUND_COLOR),
                CenterTextDrawable(placeholder, color),
            )
        val icon = createIconBitmap(drawable, IconNormalizer.ICON_VISIBLE_AREA_FACTOR)
        return BitmapInfo(
            icon = icon,
            color = color,
            defaultIconShape = defaultIconShape,
            flags = if (drawFullBleedIcons) BitmapInfo.FLAG_FULL_BLEED else 0,
        )
    }

    fun createIconBitmap(icon: Bitmap): BitmapInfo {
        val updatedIcon =
            if (iconBitmapSize != icon.width || iconBitmapSize != icon.height)
                createIconBitmap(BitmapDrawable(mContext.resources, icon), 1f)
            else icon

        return of(updatedIcon, findDominantColorByHue(updatedIcon), defaultIconShape)
    }

    /**
     * Creates bitmap using the source drawable and various parameters. The bitmap is visually
     * normalized with other icons and has enough spacing to add shadow.
     *
     * @param icon source of the icon
     * @return a bitmap suitable for displaying as an icon at various system UIs.
     */
    @JvmOverloads
    fun createBadgedIconBitmap(icon: Drawable?, options: IconOptions? = null): BitmapInfo {
        var tempIcon =
            icon
                ?: return BitmapInfo(
                    icon = BitmapRenderer.createSoftwareBitmap(iconBitmapSize, iconBitmapSize) {},
                    color = 0,
                )
        if (options != null && options.mIsFullBleed && icon is BitmapDrawable) {
            // If the source is a full-bleed icon, create an adaptive icon by insetting this icon to
            // the extra padding
            var inset = AdaptiveIconDrawable.getExtraInsetFraction()
            inset /= (1 + 2 * inset)
            tempIcon =
                AdaptiveIconDrawable(
                    ColorDrawable(Color.BLACK),
                    InsetDrawable(icon, inset, inset, inset, inset),
                )
        }

        val adaptiveIcon = wrapToAdaptiveIcon(tempIcon, options)
        val bitmap =
            createIconBitmap(
                adaptiveIcon,
                IconNormalizer.ICON_VISIBLE_AREA_FACTOR,
                options?.mGenerationMode ?: MODE_WITH_SHADOW,
                drawFullBleedIcons,
            )
        val color = options?.mExtractedColor ?: findDominantColorByHue(bitmap)
        var info = of(bitmap, color, defaultIconShape)

        var flagOp = getBitmapFlagOp(options)
        if (adaptiveIcon is WrappedAdaptiveIcon) {
            flagOp = flagOp.addFlag(BitmapInfo.FLAG_WRAPPED_NON_ADAPTIVE)
        }
        if (drawFullBleedIcons) flagOp = flagOp.addFlag(BitmapInfo.FLAG_FULL_BLEED)
        info = info.withFlags(flagOp)

        if (adaptiveIcon is Extender) {
            info = adaptiveIcon.getUpdatedBitmapInfo(info, this)
        }

        if (IconProvider.ATLEAST_T && themeController != null) {
            info =
                info.copy(
                    themedBitmap =
                        themeController.createThemedBitmap(
                            adaptiveIcon,
                            info,
                            this,
                            options?.mSourceHint,
                        )
                )
        } else if (extendibleThemeManager()) {
            info = info.copy(themedBitmap = ThemedBitmap.NOT_SUPPORTED)
        }

        return info
    }

    fun getBitmapFlagOp(options: IconOptions?): FlagOp {
        if (options == null) return FlagOp.NO_OP
        var op = FlagOp.NO_OP
        if (options.mIsInstantApp) op = op.addFlag(BitmapInfo.FLAG_INSTANT)

        val info = options.mUserIconInfo ?: options.mUserHandle?.let { getUserInfo(it) }
        if (info != null) op = info.applyBitmapInfoFlags(op)
        return op
    }

    protected open fun getUserInfo(user: UserHandle): UserIconInfo {
        val key = user.hashCode()
        /*
         * We do not have the ability to distinguish between different badged users here.
         * As such all badged users will have the work profile badge applied.
         */
        return cachedUserInfo[key]
            ?: UserIconInfo(user, if (user.isWorkUser()) TYPE_WORK else TYPE_MAIN).also {
                cachedUserInfo[key] = it
            }
    }

    /** Simple check to check if the provided user is work profile or not based on badging */
    private fun UserHandle.isWorkUser() =
        NoopDrawable().let { d -> d !== mContext.packageManager.getUserBadgedIcon(d, this) }

    fun createScaledBitmap(icon: Drawable, @BitmapGenerationMode mode: Int): Bitmap {
        return createIconBitmap(
            wrapToAdaptiveIcon(icon),
            IconNormalizer.ICON_VISIBLE_AREA_FACTOR,
            mode,
            false,
        )
    }

    /** Returns a drawable which draws the original drawable at a fixed scale */
    private fun createScaledDrawable(main: Drawable, scale: Float): Drawable {
        val h = main.intrinsicHeight.toFloat()
        val w = main.intrinsicWidth.toFloat()
        var scaleX = scale
        var scaleY = scale
        if (h > w && w > 0) {
            scaleX *= w / h
        } else if (w > h && h > 0) {
            scaleY *= h / w
        }
        scaleX = (1 - scaleX) / 2
        scaleY = (1 - scaleY) / 2
        return InsetDrawable(main, scaleX, scaleY, scaleX, scaleY)
    }

    /** Wraps the provided icon in an adaptive icon drawable */
    @JvmOverloads
    fun wrapToAdaptiveIcon(icon: Drawable, options: IconOptions? = null): AdaptiveIconDrawable =
        icon as? AdaptiveIconDrawable
            ?: WrappedAdaptiveIcon(
                    ColorDrawable(options?.mWrapperBackgroundColor ?: DEFAULT_WRAPPER_BACKGROUND),
                    createScaledDrawable(
                        icon,
                        IconNormalizer(iconBitmapSize).getScale(icon) * LEGACY_ICON_SCALE,
                    ),
                )
                .apply { setBounds(0, 0, 1, 1) }

    @JvmOverloads
    fun createIconBitmap(
        icon: Drawable?,
        scale: Float,
        @BitmapGenerationMode bitmapGenerationMode: Int = MODE_DEFAULT,
        isFullBleed: Boolean = drawFullBleedIcons,
    ): Bitmap {
        val size = iconBitmapSize
        val bitmap =
            when (bitmapGenerationMode) {
                MODE_ALPHA -> Bitmap.createBitmap(size, size, ALPHA_8)
                MODE_HARDWARE,
                MODE_HARDWARE_WITH_SHADOW -> {
                    return BitmapRenderer.createHardwareBitmap(size, size) { canvas: Canvas ->
                        drawIconBitmap(canvas, icon, scale, bitmapGenerationMode, null, isFullBleed)
                    }
                }

                MODE_WITH_SHADOW -> Bitmap.createBitmap(size, size, ARGB_8888)
                else -> Bitmap.createBitmap(size, size, ARGB_8888)
            }
        if (icon == null) return bitmap
        mCanvas.setBitmap(bitmap)
        drawIconBitmap(mCanvas, icon, scale, bitmapGenerationMode, bitmap, isFullBleed)
        mCanvas.setBitmap(null)
        return bitmap
    }

    private fun drawIconBitmap(
        canvas: Canvas,
        icon: Drawable?,
        scale: Float,
        @BitmapGenerationMode bitmapGenerationMode: Int,
        targetBitmap: Bitmap?,
        isFullBleed: Boolean,
    ) {
        val size = iconBitmapSize
        mOldBounds.set(icon?.bounds ?: return)
        val isFullBleedEnabled = isFullBleed && Flags.enableLauncherIconShapes()
        if (icon is AdaptiveIconDrawable) {
            // We are ignoring KEY_SHADOW_DISTANCE because regular icons ignore this at the
            // moment b/298203449
            val offset =
                if (isFullBleedEnabled) 0
                else max((ceil(BLUR_FACTOR * size)).toInt(), Math.round(size * (1 - scale) / 2))
            // b/211896569: AdaptiveIconDrawable do not work properly for non top-left bounds
            val newBounds = size - offset * 2
            icon.setBounds(0, 0, newBounds, newBounds)
            canvas.transformed {
                translate(offset.toFloat(), offset.toFloat())
                if (
                    (bitmapGenerationMode == MODE_WITH_SHADOW ||
                        bitmapGenerationMode == MODE_HARDWARE_WITH_SHADOW) && !isFullBleedEnabled
                ) {
                    shadowGenerator.addPathShadow(icon.iconMask, canvas)
                }
                if (icon is Extender) {
                    icon.drawForPersistence()
                }
                drawAdaptiveIcon(canvas, icon, isFullBleedEnabled)
            }
        } else {
            if (icon is BitmapDrawable && icon.bitmap?.density == Bitmap.DENSITY_NONE) {
                icon.setTargetDensity(mContext.resources.displayMetrics)
            }
            var width = size
            var height = size

            val intrinsicWidth = icon.intrinsicWidth
            val intrinsicHeight = icon.intrinsicHeight
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                // Scale the icon proportionally to the icon dimensions
                val ratio = intrinsicWidth.toFloat() / intrinsicHeight
                if (intrinsicWidth > intrinsicHeight) {
                    height = (width / ratio).toInt()
                } else if (intrinsicHeight > intrinsicWidth) {
                    width = (height * ratio).toInt()
                }
            }
            val left = (size - width) / 2
            val top = (size - height) / 2
            icon.setBounds(left, top, left + width, top + height)

            canvas.transformed {
                scale(scale, scale, (size / 2).toFloat(), (size / 2).toFloat())
                icon.draw(canvas)
            }

            if (bitmapGenerationMode == MODE_WITH_SHADOW && targetBitmap != null) {
                // Shadow extraction only works in software mode
                shadowGenerator.drawShadow(targetBitmap, canvas)

                // Draw the icon again on top:
                canvas.transformed {
                    scale(scale, scale, (size / 2).toFloat(), (size / 2).toFloat())
                    icon.draw(canvas)
                }
            }
        }
        icon.bounds = mOldBounds
    }

    /**
     * Draws AdaptiveIconDrawable onto canvas with either default shape, or as Full-bleed.
     *
     * @param canvas canvas to draw on
     * @param drawable AdaptiveIconDrawable to draw
     * @param isFullBleed whether to draw as full-bleed.
     */
    private fun drawAdaptiveIcon(
        canvas: Canvas,
        drawable: AdaptiveIconDrawable,
        isFullBleed: Boolean,
    ) {
        val background = drawable.background
        val foreground = drawable.foreground
        val shouldNotDrawFullBleed = !isFullBleed || (background == null && foreground == null)
        if (shouldNotDrawFullBleed) {
            drawable.draw(canvas)
            return
        }
        canvas.drawColor(Color.BLACK)
        background?.draw(canvas)
        foreground?.draw(canvas)
    }

    override fun close() = clear()

    protected fun clear() {}

    fun makeDefaultIcon(iconProvider: IconProvider): BitmapInfo {
        return createBadgedIconBitmap(iconProvider.getFullResDefaultActivityIcon(fullResIconDpi))
    }

    class IconOptions {
        var mIsInstantApp: Boolean = false

        var mIsFullBleed: Boolean = false

        @BitmapGenerationMode var mGenerationMode: Int = MODE_WITH_SHADOW

        var mUserHandle: UserHandle? = null
        var mUserIconInfo: UserIconInfo? = null

        @ColorInt var mExtractedColor: Int? = null

        var mSourceHint: SourceHint? = null

        var mWrapperBackgroundColor = DEFAULT_WRAPPER_BACKGROUND

        /** User for this icon, in case of badging */
        fun setUser(user: UserHandle?) = apply { mUserHandle = user }

        /** User for this icon, in case of badging */
        fun setUser(user: UserIconInfo?) = apply { mUserIconInfo = user }

        /** If this icon represents an instant app */
        fun setInstantApp(instantApp: Boolean) = apply { mIsInstantApp = instantApp }

        /**
         * If the icon is [BitmapDrawable], assumes that it is a full bleed icon and tries to shape
         * it accordingly
         */
        fun assumeFullBleedIcon(isFullBleed: Boolean) = apply { mIsFullBleed = isFullBleed }

        /** Disables auto color extraction and overrides the color to the provided value */
        fun setExtractedColor(@ColorInt color: Int) = apply { mExtractedColor = color }

        /**
         * Sets the bitmap generation mode to use for the bitmap info. Note that some generation
         * modes do not support color extraction, so consider setting a extracted color manually in
         * those cases.
         */
        fun setBitmapGenerationMode(@BitmapGenerationMode generationMode: Int) = apply {
            mGenerationMode = generationMode
        }

        /** User for this icon, in case of badging */
        fun setSourceHint(sourceHint: SourceHint?) = apply { mSourceHint = sourceHint }

        /** Sets the background color used for wrapped adaptive icon */
        fun setWrapperBackgroundColor(color: Int) = apply {
            mWrapperBackgroundColor =
                if (Color.alpha(color) < 255) DEFAULT_WRAPPER_BACKGROUND else color
        }
    }

    private class NoopDrawable : ColorDrawable() {
        override fun getIntrinsicHeight(): Int = 1

        override fun getIntrinsicWidth(): Int = 1
    }

    private class CenterTextDrawable(private val mText: String, color: Int) : ColorDrawable() {
        private val textBounds = Rect()
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { it.color = color }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            textPaint.textSize = bounds.height() / 3f
            textPaint.getTextBounds(mText, 0, mText.length, textBounds)
            canvas.drawText(
                mText,
                bounds.exactCenterX() - textBounds.exactCenterX(),
                bounds.exactCenterY() - textBounds.exactCenterY(),
                textPaint,
            )
        }
    }

    private class WrappedAdaptiveIcon(
        backgroundDrawable: Drawable?,
        foregroundDrawable: Drawable?,
    ) : AdaptiveIconDrawable(backgroundDrawable, foregroundDrawable)

    companion object {
        private const val DEFAULT_WRAPPER_BACKGROUND = Color.WHITE
        private val LEGACY_ICON_SCALE =
            .7f * (1f / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction()))

        const val MODE_DEFAULT: Int = 0
        const val MODE_ALPHA: Int = 1
        const val MODE_WITH_SHADOW: Int = 2
        const val MODE_HARDWARE: Int = 3
        const val MODE_HARDWARE_WITH_SHADOW: Int = 4

        private const val ICON_BADGE_SCALE = 0.444f

        private val PLACEHOLDER_BACKGROUND_COLOR = Color.rgb(245, 245, 245)

        /** Returns the correct badge size given an icon size */
        @JvmStatic
        fun getBadgeSizeForIconSize(iconSize: Int): Int {
            return (ICON_BADGE_SCALE * iconSize).toInt()
        }
    }
}
