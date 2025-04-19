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

package com.android.systemui.monet;

import com.google.ux.material.libmonet.dynamiccolor.ContrastCurve;
import com.google.ux.material.libmonet.dynamiccolor.DynamicColor;
import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors;
import com.google.ux.material.libmonet.dynamiccolor.ToneDeltaPair;
import com.google.ux.material.libmonet.dynamiccolor.TonePolarity;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class CustomDynamicColors {
    private final MaterialDynamicColors mMdc;
    public final List<Supplier<DynamicColor>> allColors;

    public CustomDynamicColors() {
        this.mMdc = new MaterialDynamicColors();
        allColors = Arrays.asList(
                this::widgetBackground,
                this::clockHour,
                this::clockMinute,
                this::clockSecond,
                this::weatherTemp,
                this::themeApp,
                this::onThemeApp,
                this::themeAppRing,
                this::themeNotif,
                this::brandA,
                this::brandB,
                this::brandC,
                this::brandD,
                this::underSurface,
                this::shadeActive,
                this::onShadeActive,
                this::onShadeActiveVariant,
                this::shadeInactive,
                this::onShadeInactive,
                this::onShadeInactiveVariant,
                this::shadeDisabled,
                this::overviewBackground
        );
    }

    // CLOCK COLORS
    public DynamicColor widgetBackground() {
        return new DynamicColor.Builder()
                .setName("widget_background")
                .setPalette((s) -> s.secondaryPalette)
                .setTone((s) -> s.isDark ? 20.0 : 95.0)
                .setIsBackground(true)
                .build();
    }

    public DynamicColor clockHour() {
        return new DynamicColor.Builder()
                .setName("clock_hour")
                .setPalette((s) -> s.isDark ? s.primaryPalette : s.secondaryPalette)
                .setTone((s) -> s.isDark ? 80.0 : 30.0)
                .setIsBackground(false)
                .setBackground((s) -> widgetBackground())
                .setContrastCurve((s) -> new ContrastCurve(4.0, 4.0, 5.0, 15.0))
                .setToneDeltaPair((s) -> new ToneDeltaPair(clockHour(), clockMinute(), 10.0,
                        TonePolarity.DARKER, ToneDeltaPair.DeltaConstraint.FARTHER))
                .build();
    }

    public DynamicColor clockMinute() {
        return new DynamicColor.Builder()
                .setName("clock_minute")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> s.isDark ? 90.0 : 40.0)
                .setIsBackground(false)
                .setBackground((s) -> widgetBackground())
                .setContrastCurve((s) -> new ContrastCurve(6.5, 6.5, 10.0, 15.0))
                .build();
    }

    public DynamicColor clockSecond() {
        return new DynamicColor.Builder()
                .setName("clock_second")
                .setPalette((s) -> s.tertiaryPalette)
                .setTone((s) -> s.isDark ? 90.0 : 40.0)
                .setIsBackground(false)
                .setBackground((s) -> widgetBackground())
                .setContrastCurve((s) -> new ContrastCurve(5.0, 5.0, 70.0, 11.0))
                .build();
    }

    public DynamicColor weatherTemp() {
        return new DynamicColor.Builder()
                .setName("weather_temp")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> s.isDark ? 80.0 : 40.0)
                .setIsBackground(false)
                .setBackground((s) -> widgetBackground())
                .setContrastCurve((s) -> new ContrastCurve(5.0, 5.0, 70.0, 11.0))
                .build();
    }

    // THEME APP ICONS
    public DynamicColor themeApp() {
        return new DynamicColor.Builder()
                .setName("theme_app")
                .setPalette((s) -> s.isDark ? s.secondaryPalette : s.primaryPalette)
                .setTone((s) -> s.isDark ? 20.0 : 90.0)
                .setIsBackground(true)
                .build();
    }

    public DynamicColor onThemeApp() {
        return new DynamicColor.Builder()
                .setName("on_theme_app")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> s.isDark ? 80.0 : 30.0)
                .setIsBackground(false)
                .setBackground((s) -> themeApp())
                .setContrastCurve((s) -> new ContrastCurve(3.0, 3.0, 7.0, 10.0))
                .build();
    }

    public DynamicColor themeAppRing() {
        return new DynamicColor.Builder()
                .setName("theme_app_ring")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> 70.0)
                .setIsBackground(true)
                .build();
    }

    public DynamicColor themeNotif() {
        return new DynamicColor.Builder()
                .setName("theme_notif")
                .setPalette((s) -> s.tertiaryPalette)
                .setTone((s) -> 80.0)
                .setBackground((s) -> themeAppRing())
                .setContrastCurve((s) -> new ContrastCurve(1.0, 1.0, 1.0, 1.0))
                .setToneDeltaPair((s) -> new ToneDeltaPair(themeNotif(), themeAppRing(), 10.0,
                        TonePolarity.RELATIVE_LIGHTER, ToneDeltaPair.DeltaConstraint.FARTHER))
                .build();
    }

    // SUPER G COLORS
    public DynamicColor brandA() {
        return new DynamicColor.Builder()
                .setName("brand_a")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> s.isDark ? 80.0 : 40.0)
                .setBackground((s) -> mMdc.surfaceContainerLow())
                .setContrastCurve((s) -> s.isDark ? new ContrastCurve(10.0, 10.0, 12.0, 13.0)
                        : new ContrastCurve(6.0, 6.0, 9.0, 12.0))
                .build();
    }

    public DynamicColor brandB() {
        return new DynamicColor.Builder()
                .setName("brand_b")
                .setPalette((s) -> s.secondaryPalette)
                .setTone((s) -> s.isDark ? 98.0 : 70.0)
                .setBackground((s) -> mMdc.surfaceContainerLow())
                .setContrastCurve((s) -> s.isDark ? new ContrastCurve(16.0, 16.0, 16.5, 17.0)
                        : new ContrastCurve(2.0, 2.0, 3.0, 4.5))
                .build();
    }

    public DynamicColor brandC() {
        return new DynamicColor.Builder()
                .setName("brand_c")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> s.isDark ? 60.0 : 50.0)
                .setBackground((s) -> mMdc.surfaceContainerLow())
                .setContrastCurve((s) -> s.isDark ? new ContrastCurve(6.0, 6.0, 9.0, 11.0)
                        : new ContrastCurve(4.0, 4.0, 7.0, 8.0))
                .build();
    }

    public DynamicColor brandD() {
        return new DynamicColor.Builder()
                .setName("brand_d")
                .setPalette((s) -> s.tertiaryPalette)
                .setTone((s) -> s.isDark ? 90.0 : 59.0)
                .setBackground((s) -> mMdc.surfaceContainerLow())
                .setContrastCurve((s) -> s.isDark ? new ContrastCurve(13.0, 13.0, 14.0, 15.0)
                        : new ContrastCurve(3.0, 3.0, 4.5, 6.0))
                .build();
    }

    // QUICK SETTING TILES
    public DynamicColor underSurface() {
        return new DynamicColor.Builder()
                .setName("under_surface")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> 0.0)
                .setIsBackground(true)
                .build();
    }

    public DynamicColor shadeActive() {
        return new DynamicColor.Builder()
                .setName("shade_active")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> 90.0)
                .setIsBackground(true)
                .setBackground((s) -> underSurface())
                .setContrastCurve((s) -> new ContrastCurve(3.0, 3.0, 4.5, 7.0))
                .setToneDeltaPair((s) -> new ToneDeltaPair(shadeActive(), shadeInactive(), 30.0,
                        TonePolarity.LIGHTER, ToneDeltaPair.DeltaConstraint.FARTHER))
                .build();
    }

    public DynamicColor onShadeActive() {
        return new DynamicColor.Builder()
                .setName("on_shade_active")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> 10.0)
                .setIsBackground(false)
                .setBackground((s) -> shadeActive())
                .setContrastCurve((s) -> new ContrastCurve(4.5, 4.5, 7.0, 11.0))
                .setToneDeltaPair(
                        (s) -> new ToneDeltaPair(onShadeActive(), onShadeActiveVariant(), 20.0,
                                TonePolarity.RELATIVE_LIGHTER,
                                ToneDeltaPair.DeltaConstraint.FARTHER))
                .build();
    }

    public DynamicColor onShadeActiveVariant() {
        return new DynamicColor.Builder()
                .setName("on_shade_active_variant")
                .setPalette((s) -> s.primaryPalette)
                .setTone((s) -> 30.0)
                .setIsBackground(false)
                .setBackground((s) -> shadeActive())
                .setContrastCurve((s) -> new ContrastCurve(4.5, 4.5, 7.0, 11.0))
                .build();
    }

    public DynamicColor shadeInactive() {
        return new DynamicColor.Builder()
                .setName("shade_inactive")
                .setPalette((s) -> s.neutralPalette)
                .setTone((s) -> 20.0)
                .setIsBackground(true)
                .setBackground((s) -> underSurface())
                .setContrastCurve((s) -> new ContrastCurve(1.0, 1.0, 1.0, 1.0))
                .setToneDeltaPair((s) -> new ToneDeltaPair(shadeInactive(), shadeDisabled(), 15.0,
                        TonePolarity.LIGHTER, ToneDeltaPair.DeltaConstraint.FARTHER))
                .build();
    }

    public DynamicColor onShadeInactive() {
        return new DynamicColor.Builder()
                .setName("on_shade_inactive")
                .setPalette((s) -> s.neutralVariantPalette)
                .setTone((s) -> 90.0)
                .setIsBackground(false)
                .setBackground((s) -> shadeInactive())
                .setContrastCurve((s) -> new ContrastCurve(4.5, 4.5, 7.0, 11.0))
                .setToneDeltaPair(
                        (s) -> new ToneDeltaPair(onShadeInactive(), onShadeInactiveVariant(), 10.0,
                                TonePolarity.RELATIVE_LIGHTER,
                                ToneDeltaPair.DeltaConstraint.FARTHER))
                .build();
    }

    public DynamicColor onShadeInactiveVariant() {
        return new DynamicColor.Builder()
                .setName("on_shade_inactive_variant")
                .setPalette((s) -> s.neutralVariantPalette)
                .setTone((s) -> 80.0)
                .setIsBackground(false)
                .setBackground((s) -> shadeInactive())
                .setContrastCurve((s) -> new ContrastCurve(4.5, 4.5, 7.0, 11.0))
                .build();
    }

    public DynamicColor shadeDisabled() {
        return new DynamicColor.Builder()
                .setName("shade_disabled")
                .setPalette((s) -> s.neutralPalette)
                .setTone((s) -> 4.0)
                .setIsBackground(false)
                .setBackground((s) -> underSurface())
                .setContrastCurve((s) -> new ContrastCurve(1.0, 1.0, 1.0, 1.0))
                .build();
    }

    public DynamicColor overviewBackground() {
        return new DynamicColor.Builder()
                .setName("overview_background")
                .setPalette((s) -> s.neutralVariantPalette)
                .setTone((s) -> s.isDark ? 35.0 : 80.0)
                .setIsBackground(true)
                .build();
    }
}
