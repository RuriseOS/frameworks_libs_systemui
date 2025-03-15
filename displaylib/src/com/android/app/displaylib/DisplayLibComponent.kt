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
package com.android.app.displaylib

import android.hardware.display.DisplayManager
import android.os.Handler
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Component that creates all classes in displaylib.
 *
 * Each user of this library will bind the required element in the factory constructor. It's advised
 * to use this component through [createDisplayLibComponent], which wraps the dagger generated
 * method.
 */
@Component(modules = [DisplayLibModule::class])
@Singleton
interface DisplayLibComponent {

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance displayManager: DisplayManager,
            @BindsInstance bgHandler: Handler,
            @BindsInstance bgApplicationScope: CoroutineScope,
            @BindsInstance backgroundCoroutineDispatcher: CoroutineDispatcher,
        ): DisplayLibComponent
    }

    val displayRepository: DisplayRepository
}

@Module
interface DisplayLibModule {
    @Binds fun bindDisplayManagerImpl(impl: DisplayRepositoryImpl): DisplayRepository
}

/**
 * Just a wrapper to make the generated code to create the component more explicit.
 *
 * This should be called only once per process. Note that [bgHandler], [bgApplicationScope] and
 * [backgroundCoroutineDispatcher] are expected to be backed by background threads. In the future
 * this might throw an exception if they are tied to the main thread!
 */
fun createDisplayLibComponent(
    displayManager: DisplayManager,
    bgHandler: Handler,
    bgApplicationScope: CoroutineScope,
    backgroundCoroutineDispatcher: CoroutineDispatcher,
): DisplayLibComponent {
    return DaggerDisplayLibComponent.factory()
        .create(displayManager, bgHandler, bgApplicationScope, backgroundCoroutineDispatcher)
}
