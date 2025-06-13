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
package com.android.launcher3.icons;

import static com.android.launcher3.icons.FastBitmapDrawable.FULLY_OPAQUE;
import static com.android.launcher3.icons.FastBitmapDrawable.getDisabledColorFilter;
import static com.android.launcher3.icons.IconProvider.ATLEAST_T;
import static com.android.launcher3.icons.cache.CacheLookupFlag.DEFAULT_LOOKUP_FLAG;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.launcher3.icons.FastBitmapDrawableDelegate.DelegateFactory;
import com.android.launcher3.icons.cache.CacheLookupFlag;
import com.android.launcher3.icons.mono.ThemedIconDelegate;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Wrapper over {@link AdaptiveIconDrawable} to intercept icon flattening logic for dynamic
 * clock icons
 */
public class ClockDrawableWrapper extends AdaptiveIconDrawable implements BitmapInfo.Extender {

    public static boolean sRunningInTest = false;

    private static final String TAG = "ClockDrawableWrapper";

    private static final boolean DISABLE_SECONDS = true;
    private static final int NO_COLOR = -1;

    // Time after which the clock icon should check for an update. The actual invalidate
    // will only happen in case of any change.
    public static final long TICK_MS = DISABLE_SECONDS ? TimeUnit.MINUTES.toMillis(1) : 200L;

    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";
    private static final String ROUND_ICON_METADATA_KEY = LAUNCHER_PACKAGE
            + ".LEVEL_PER_TICK_ICON_ROUND";
    private static final String HOUR_INDEX_METADATA_KEY = LAUNCHER_PACKAGE + ".HOUR_LAYER_INDEX";
    private static final String MINUTE_INDEX_METADATA_KEY = LAUNCHER_PACKAGE
            + ".MINUTE_LAYER_INDEX";
    private static final String SECOND_INDEX_METADATA_KEY = LAUNCHER_PACKAGE
            + ".SECOND_LAYER_INDEX";
    private static final String DEFAULT_HOUR_METADATA_KEY = LAUNCHER_PACKAGE
            + ".DEFAULT_HOUR";
    private static final String DEFAULT_MINUTE_METADATA_KEY = LAUNCHER_PACKAGE
            + ".DEFAULT_MINUTE";
    private static final String DEFAULT_SECOND_METADATA_KEY = LAUNCHER_PACKAGE
            + ".DEFAULT_SECOND";

    /* Number of levels to jump per second for the second hand */
    private static final int LEVELS_PER_SECOND = 10;

    public static final int INVALID_VALUE = -1;

    private final AnimationInfo mAnimationInfo = new AnimationInfo();
    private AnimationInfo mThemeInfo = null;

    private ClockDrawableWrapper(AdaptiveIconDrawable base) {
        super(base.getBackground(), base.getForeground());
    }

    @Override
    public Drawable getMonochrome() {
        if (mThemeInfo == null) {
            return null;
        }
        Drawable d = mThemeInfo.baseDrawableState.newDrawable().mutate();
        if (d instanceof AdaptiveIconDrawable) {
            Drawable mono = ((AdaptiveIconDrawable) d).getForeground();
            mThemeInfo.applyTime(Calendar.getInstance(), (LayerDrawable) mono);
            return mono;
        }
        return null;
    }

    /**
     * Loads and returns the wrapper from the provided package, or returns null
     * if it is unable to load.
     */
    public static ClockDrawableWrapper forPackage(Context context, String pkg, int iconDpi) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo =  pm.getApplicationInfo(pkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.GET_META_DATA);
            Resources res = pm.getResourcesForApplication(appInfo);
            return forExtras(appInfo.metaData, resId -> res.getDrawableForDensity(resId, iconDpi));
        } catch (Exception e) {
            Log.d(TAG, "Unable to load clock drawable info", e);
        }
        return null;
    }

    private static ClockDrawableWrapper forExtras(
            Bundle metadata, IntFunction<Drawable> drawableProvider) {
        if (metadata == null) {
            return null;
        }
        int drawableId = metadata.getInt(ROUND_ICON_METADATA_KEY, 0);
        if (drawableId == 0) {
            return null;
        }

        Drawable drawable = drawableProvider.apply(drawableId).mutate();
        if (!(drawable instanceof AdaptiveIconDrawable)) {
            return null;
        }
        AdaptiveIconDrawable aid = (AdaptiveIconDrawable) drawable;

        ClockDrawableWrapper wrapper = new ClockDrawableWrapper(aid);
        AnimationInfo info = wrapper.mAnimationInfo;

        info.baseDrawableState = drawable.getConstantState();
        info.hourLayerIndex = metadata.getInt(HOUR_INDEX_METADATA_KEY, INVALID_VALUE);
        info.minuteLayerIndex = metadata.getInt(MINUTE_INDEX_METADATA_KEY, INVALID_VALUE);
        info.secondLayerIndex = metadata.getInt(SECOND_INDEX_METADATA_KEY, INVALID_VALUE);

        info.defaultHour = metadata.getInt(DEFAULT_HOUR_METADATA_KEY, 0);
        info.defaultMinute = metadata.getInt(DEFAULT_MINUTE_METADATA_KEY, 0);
        info.defaultSecond = metadata.getInt(DEFAULT_SECOND_METADATA_KEY, 0);

        LayerDrawable foreground = (LayerDrawable) wrapper.getForeground();
        int layerCount = foreground.getNumberOfLayers();
        if (info.hourLayerIndex < 0 || info.hourLayerIndex >= layerCount) {
            info.hourLayerIndex = INVALID_VALUE;
        }
        if (info.minuteLayerIndex < 0 || info.minuteLayerIndex >= layerCount) {
            info.minuteLayerIndex = INVALID_VALUE;
        }
        if (info.secondLayerIndex < 0 || info.secondLayerIndex >= layerCount) {
            info.secondLayerIndex = INVALID_VALUE;
        } else if (DISABLE_SECONDS) {
            foreground.setDrawable(info.secondLayerIndex, null);
            info.secondLayerIndex = INVALID_VALUE;
        }

        if (ATLEAST_T && aid.getMonochrome() instanceof LayerDrawable) {
            wrapper.mThemeInfo = info.copyForIcon(new AdaptiveIconDrawable(
                    new ColorDrawable(Color.WHITE), aid.getMonochrome().mutate()));
        }
        info.applyTime(Calendar.getInstance(), foreground);
        return wrapper;
    }

    @Override
    public ClockBitmapInfo getExtendedInfo(Bitmap bitmap, int color,
            BaseIconFactory iconFactory, float normalizationScale) {
        AdaptiveIconDrawable background = new AdaptiveIconDrawable(
                getBackground().getConstantState().newDrawable(), null);
        Bitmap flattenBG = iconFactory.createScaledBitmap(background,
                BaseIconFactory.MODE_HARDWARE_WITH_SHADOW);

        // Only pass theme info if mono-icon is enabled
        AnimationInfo themeInfo = iconFactory.getThemeController() != null ? mThemeInfo : null;
        Bitmap themeBG = themeInfo == null ? null : iconFactory.getWhiteShadowLayer();
        return new ClockBitmapInfo(bitmap, color, normalizationScale,
                mAnimationInfo, flattenBG, themeInfo, themeBG);
    }

    @Override
    public void drawForPersistence(Canvas canvas) {
        LayerDrawable foreground = (LayerDrawable) getForeground();
        resetLevel(foreground, mAnimationInfo.hourLayerIndex);
        resetLevel(foreground, mAnimationInfo.minuteLayerIndex);
        resetLevel(foreground, mAnimationInfo.secondLayerIndex);
        draw(canvas);
        mAnimationInfo.applyTime(Calendar.getInstance(), (LayerDrawable) getForeground());
    }

    private void resetLevel(LayerDrawable drawable, int index) {
        if (index != INVALID_VALUE) {
            drawable.getDrawable(index).setLevel(0);
        }
    }

    private static class AnimationInfo {

        public ConstantState baseDrawableState;

        public int hourLayerIndex;
        public int minuteLayerIndex;
        public int secondLayerIndex;
        public int defaultHour;
        public int defaultMinute;
        public int defaultSecond;

        public AnimationInfo copyForIcon(Drawable icon) {
            AnimationInfo result = new AnimationInfo();
            result.baseDrawableState = icon.getConstantState();
            result.defaultHour = defaultHour;
            result.defaultMinute = defaultMinute;
            result.defaultSecond = defaultSecond;
            result.hourLayerIndex = hourLayerIndex;
            result.minuteLayerIndex = minuteLayerIndex;
            result.secondLayerIndex = secondLayerIndex;
            return result;
        }

        boolean applyTime(Calendar time, LayerDrawable foregroundDrawable) {
            time.setTimeInMillis(System.currentTimeMillis());

            // We need to rotate by the difference from the default time if one is specified.
            int convertedHour = (time.get(Calendar.HOUR) + (12 - defaultHour)) % 12;
            int convertedMinute = (time.get(Calendar.MINUTE) + (60 - defaultMinute)) % 60;
            int convertedSecond = (time.get(Calendar.SECOND) + (60 - defaultSecond)) % 60;

            boolean invalidate = false;
            if (hourLayerIndex != INVALID_VALUE) {
                final Drawable hour = foregroundDrawable.getDrawable(hourLayerIndex);
                if (hour.setLevel(convertedHour * 60 + time.get(Calendar.MINUTE))) {
                    invalidate = true;
                }
            }

            if (minuteLayerIndex != INVALID_VALUE) {
                final Drawable minute = foregroundDrawable.getDrawable(minuteLayerIndex);
                if (minute.setLevel(time.get(Calendar.HOUR) * 60 + convertedMinute)) {
                    invalidate = true;
                }
            }

            if (secondLayerIndex != INVALID_VALUE) {
                final Drawable second = foregroundDrawable.getDrawable(secondLayerIndex);
                if (second.setLevel(convertedSecond * LEVELS_PER_SECOND)) {
                    invalidate = true;
                }
            }

            return invalidate;
        }
    }

    static class ClockBitmapInfo extends BitmapInfo {

        public final float boundsOffset;

        public final AnimationInfo animInfo;
        public final Bitmap mFlattenedBackground;

        public final AnimationInfo themeData;
        public final Bitmap themeBackground;

        ClockBitmapInfo(Bitmap icon, int color, float scale,
                AnimationInfo animInfo, Bitmap background,
                AnimationInfo themeInfo, Bitmap themeBackground) {
            super(icon, color, /* flags */ 0, /* themedBitmap */ null);
            this.boundsOffset = Math.max(ShadowGenerator.BLUR_FACTOR, (1 - scale) / 2);
            this.animInfo = animInfo;
            this.mFlattenedBackground = background;
            this.themeData = themeInfo;
            this.themeBackground = themeBackground;
        }

        @Override
        @TargetApi(Build.VERSION_CODES.TIRAMISU)
        public FastBitmapDrawable newIcon(Context context,
                @DrawableCreationFlags int creationFlags, Path badgeShape) {
            AnimationInfo info;
            Bitmap bg;
            int themedFgColor;
            ColorFilter bgFilter;
            if ((creationFlags & FLAG_THEMED) != 0 && themeData != null) {
                int[] colors = ThemedIconDelegate.getColors(context);
                Drawable tintedDrawable = themeData.baseDrawableState.newDrawable().mutate();
                themedFgColor = colors[1];
                tintedDrawable.setTint(colors[1]);
                info = themeData.copyForIcon(tintedDrawable);
                bg = themeBackground;
                bgFilter = new BlendModeColorFilter(colors[0], BlendMode.SRC_IN);
            } else {
                info = animInfo;
                themedFgColor = NO_COLOR;
                bg = mFlattenedBackground;
                bgFilter = null;
            }
            if (info == null) {
                return super.newIcon(context, creationFlags);
            }

            ClockDelegateInfo delegateInfo =
                    new ClockDelegateInfo(themedFgColor, boundsOffset, animInfo, bg, bgFilter);
            FastBitmapDrawable d = new FastBitmapDrawable(this, delegateInfo);
            applyFlags(context, d, creationFlags, null);
            return d;
        }

        @Override
        public boolean canPersist() {
            return false;
        }

        @Override
        public BitmapInfo clone() {
            return copyInternalsTo(new ClockBitmapInfo(icon, color,
                    1 - 2 * boundsOffset, animInfo, mFlattenedBackground,
                    themeData, themeBackground));
        }

        @Override
        public CacheLookupFlag getMatchingLookupFlag() {
            return DEFAULT_LOOKUP_FLAG.withThemeIcon(themeData != null);
        }
    }

    private record ClockDelegateInfo(
            int themeFgColor, float boundsOffset, AnimationInfo animInfo, Bitmap bg,
            ColorFilter bgFilter) implements DelegateFactory {

        @NonNull
        @Override
        public FastBitmapDrawableDelegate newDelegate(@NonNull BitmapInfo bitmapInfo,
                @NonNull Paint paint, @NonNull FastBitmapDrawable host) {
            return new ClockDrawableDelegate(this, host);
        }
    }

    private static class ClockDrawableDelegate implements FastBitmapDrawableDelegate, Runnable {

        private final Calendar mTime = Calendar.getInstance();

        private final FastBitmapDrawable mHost;
        private final float mBoundsOffset;
        private final AnimationInfo mAnimInfo;

        private final Bitmap mBG;
        private final Paint mBgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final ColorFilter mBgFilter;
        private final int mThemedFgColor;

        private final AdaptiveIconDrawable mFullDrawable;
        private final LayerDrawable mFG;
        private final float mCanvasScale;

        ClockDrawableDelegate(ClockDelegateInfo cs, FastBitmapDrawable host) {
            mHost = host;
            mBoundsOffset = cs.boundsOffset;
            mAnimInfo = cs.animInfo;

            mBG = cs.bg;
            mBgFilter = cs.bgFilter;
            mBgPaint.setColorFilter(cs.bgFilter);
            mThemedFgColor = cs.themeFgColor;

            mFullDrawable =
                    (AdaptiveIconDrawable) mAnimInfo.baseDrawableState.newDrawable().mutate();
            mFG = (LayerDrawable) mFullDrawable.getForeground();

            // Time needs to be applied here since drawInternal is NOT guaranteed to be called
            // before this foreground drawable is shown on the screen.
            mAnimInfo.applyTime(mTime, mFG);
            mCanvasScale = 1 - 2 * mBoundsOffset;
        }

        @Override
        public void setAlpha(int alpha) {
            mBgPaint.setAlpha(alpha);
            mFG.setAlpha(alpha);
        }

        @Override
        public void onBoundsChange(Rect bounds) {
            // b/211896569 AdaptiveIcon does not work properly when bounds
            // are not aligned to top/left corner
            mFullDrawable.setBounds(0, 0, bounds.width(), bounds.height());
        }

        @Override
        public void drawContent(@NonNull BitmapInfo info, @NonNull Canvas canvas,
                @NonNull Rect bounds, @NonNull Paint paint) {
            if (mAnimInfo == null) {
                FastBitmapDrawableDelegate.super.drawContent(info, canvas, bounds, paint);
                return;
            }
            canvas.drawBitmap(mBG, null, bounds, mBgPaint);

            // prepare and draw the foreground
            mAnimInfo.applyTime(mTime, mFG);
            int saveCount = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(mCanvasScale, mCanvasScale, bounds.width() / 2, bounds.height() / 2);
            canvas.clipPath(mFullDrawable.getIconMask());
            mFG.draw(canvas);
            canvas.restoreToCount(saveCount);

            reschedule();
        }

        @Override
        public boolean isThemed() {
            return mBgPaint.getColorFilter() != null;
        }

        @Override
        public void updateFilter(boolean isDisabled, float disabledAlpha) {
            int alpha = isDisabled ? (int) (disabledAlpha * FULLY_OPAQUE) : FULLY_OPAQUE;
            setAlpha(alpha);
            mBgPaint.setColorFilter(isDisabled ? getDisabledColorFilter() : mBgFilter);
            mFG.setColorFilter(isDisabled ? getDisabledColorFilter() : null);
        }

        @Override
        public int getIconColor(@NonNull BitmapInfo info) {
            return isThemed() ? mThemedFgColor
                    : FastBitmapDrawableDelegate.super.getIconColor(info);
        }

        @Override
        public void run() {
            if (mAnimInfo.applyTime(mTime, mFG)) {
                mHost.invalidateSelf();
            } else {
                reschedule();
            }
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            if (isVisible) {
                reschedule();
            } else {
                mHost.unscheduleSelf(this);
            }
        }

        private void reschedule() {
            if (!mHost.isVisible()) {
                return;
            }
            mHost.unscheduleSelf(this);
            final long upTime = SystemClock.uptimeMillis();
            final long step = TICK_MS; /* tick every 200 ms */
            mHost.scheduleSelf(this, upTime - ((upTime % step)) + step);
        }
    }
}
