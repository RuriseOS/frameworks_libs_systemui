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

package com.android.widgetpicker.shared.model

import android.appwidget.AppWidgetProviderInfo

/**
 * Raw information about a widget that can be considered for display in widget picker list.
 *
 * Note: It is widget picker's responsibility to run eligibility checks to see if the widget can be
 * displayed in picker.
 *
 * @property id a unique identifier for the widget
 * @property appId a unique identifier for the app group that this widget could belong to
 * @property label a user friendly label for the widget.
 * @property description a user friendly description for the widget
 * @property appWidgetProviderInfo widget info associated with the widget as configured by the
 *   developer; note: this should be a local clone and not the object that was received from
 *   appwidget manager.
 */
data class PickableWidget(
    val id: WidgetId,
    val appId: WidgetAppId,
    val label: String,
    val description: String,
    val appWidgetProviderInfo: AppWidgetProviderInfo,
)
