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

package com.android.widgetpicker

import dagger.Subcomponent

/**
 * A sub-component that apps can include in their dagger module to bootstrap & interact with apis
 * provided by widget picker.
 *
 * Steps:
 * 1. Include this component as a sub-component on your app's main dagger module (and remember to
 *    update Android.bp & gradle files).
 * 2. Ensure bindings necessary for repository interfaces used in [WidgetPickerModule] are provided
 *    in your app.
 * 3. Host a singleton class in your app to create an instance of widget picker component on-demand
 *    e.g. when user wants to open widget picker.
 *
 * Example for #3.
 *
 * ```
 * @MyAppSingleton
 * public class WidgetPickerProvider {
 *     private final Provider<WidgetPickerComponent.Factory> widgetPickerComponentProvider;
 *
 *     @Inject
 *     public WidgetPickerProvider(Provider<WidgetPickerComponent.Factory> widgetPickerComponentProvider) {
 *         this.widgetPickerComponentProvider = widgetPickerComponentProvider;
 *     }
 *
 *     public boolean show(...) {
 *         WidgetPickerComponent component = widgetPickerComponentProvider.get().build();
 *         WidgetPickerUiInteractor interactor = component.getWidgetInteractor();
 *         return interactor.show(...);
 *     }
 * }
 * ```
 */
@WidgetPickerSingleton
@Subcomponent(modules = [WidgetPickerModule::class])
interface WidgetPickerComponent {
    @Subcomponent.Factory
    interface Factory {
        fun build(): WidgetPickerComponent
    }

    // Interactors / APIs available for clients to interact with widget picker will go here.
    // e.g. fun getWidgetPickerUiInteractor(): WidgetPickerUiInteractor
}
