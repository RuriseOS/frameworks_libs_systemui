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

package com.android.mechanics

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.MotionValueTest.Companion.FakeGestureContext
import com.android.mechanics.spec.MotionSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionValueCollectionLifecycleTest {

    @get:Rule(order = 0) val rule = createComposeRule()

    @Test
    fun keepRunning_empty_doesNotWakeup() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)
        rule.setContent { LaunchedEffect(Unit) { underTest.keepRunning() } }

        rule.awaitIdle()
        val framesCount = underTest.frameCount
        rule.mainClock.autoAdvance = false

        assertThat(underTest.isActive).isTrue()
        assertThat(underTest.isAnimating).isFalse()

        // Update the value, but WITHOUT causing an animation
        input.floatValue = 0.5f
        rule.awaitIdle()

        assertThat(framesCount).isEqualTo(underTest.frameCount)
        assertThat(underTest.isAnimating).isFalse()

        rule.mainClock.advanceTimeByFrame()
        rule.awaitIdle()

        assertThat(framesCount).isEqualTo(underTest.frameCount)
        assertThat(underTest.isAnimating).isFalse()
    }

    @Test
    fun keepRunning_activatesExisting() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)

        val inspector = underTest.create({ MotionSpec.Identity }).debugInspector()

        assertThat(underTest.frameCount).isEqualTo(0)
        assertThat(underTest.isActive).isFalse()
        assertThat(underTest.isAnimating).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(0)
        assertThat(inspector.isActive).isFalse()
        assertThat(inspector.isAnimating).isFalse()

        rule.setContent { LaunchedEffect(Unit) { underTest.keepRunning() } }

        rule.awaitIdle()

        assertThat(underTest.frameCount).isEqualTo(1)
        assertThat(underTest.isActive).isTrue()
        assertThat(underTest.isAnimating).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(1)
        assertThat(inspector.isActive).isTrue()
        assertThat(inspector.isAnimating).isFalse()
    }

    @Test
    fun keepRunning_deavtivatesOnDispose() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)

        val motionValue = underTest.create({ MotionSpec.Identity })
        val inspector = motionValue.debugInspector()

        rule.setContent { LaunchedEffect(Unit) { underTest.keepRunning() } }

        rule.awaitIdle()

        assertThat(underTest.frameCount).isEqualTo(1)
        assertThat(underTest.isActive).isTrue()
        assertThat(underTest.isAnimating).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(1)
        assertThat(inspector.isActive).isTrue()
        assertThat(inspector.isAnimating).isFalse()

        motionValue.dispose()
        rule.awaitIdle()

        assertThat(underTest.frameCount).isEqualTo(2)
        assertThat(underTest.isActive).isTrue()
        assertThat(underTest.isAnimating).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(0)
        assertThat(inspector.isActive).isFalse()
        assertThat(inspector.isAnimating).isFalse()
    }

    @Test
    fun createAndDispose_withoutKeepRunning_isInactive() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)

        rule.setContent {}
        assertThat(underTest.isActive).isFalse()

        val motionValue = underTest.create({ MotionSpec.Identity })
        val inspector = motionValue.debugInspector()
        rule.awaitIdle()

        assertThat(underTest.isActive).isFalse()
        assertThat(inspector.isActive).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(0)

        motionValue.dispose()
        rule.awaitIdle()

        assertThat(underTest.isActive).isFalse()
        assertThat(inspector.isActive).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(0)
    }

    @Test
    fun keepRunning_activatesNew() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)

        rule.setContent { LaunchedEffect(Unit) { underTest.keepRunning() } }
        rule.awaitIdle()

        assertThat(underTest.isActive).isTrue()
        assertThat(underTest.activeComputationCount).isEqualTo(0)

        val inspector = underTest.create({ MotionSpec.Identity }).debugInspector()
        rule.awaitIdle()

        assertThat(underTest.activeComputationCount).isEqualTo(1)
        assertThat(inspector.isActive).isTrue()
    }

    @Test
    fun keepRunning_withMultipleValues() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)

        val mv1 = underTest.create({ MotionSpec.Identity })
        val inspector1 = mv1.debugInspector()
        val mv2 = underTest.create({ MotionSpec.Identity })
        val inspector2 = mv2.debugInspector()

        rule.setContent { LaunchedEffect(Unit) { underTest.keepRunning() } }
        rule.awaitIdle()

        assertThat(underTest.isActive).isTrue()
        assertThat(underTest.activeComputationCount).isEqualTo(2)
        assertThat(inspector1.isActive).isTrue()
        assertThat(inspector2.isActive).isTrue()

        mv1.dispose()
        rule.awaitIdle()

        assertThat(underTest.activeComputationCount).isEqualTo(1)
        assertThat(inspector1.isActive).isFalse()
        assertThat(inspector2.isActive).isTrue()

        mv2.dispose()
        rule.awaitIdle()

        assertThat(underTest.activeComputationCount).isEqualTo(0)
        assertThat(inspector1.isActive).isFalse()
        assertThat(inspector2.isActive).isFalse()
    }

    @Test
    fun keepRunning_cancelled_deactivates() = runTest {
        val input = mutableFloatStateOf(0f)
        val underTest = MotionValueCollection(input::value, FakeGestureContext)
        val inspector = underTest.create({ MotionSpec.Identity }).debugInspector()
        val keepRunning = mutableStateOf(true)

        rule.setContent {
            if (keepRunning.value) {
                LaunchedEffect(Unit) { underTest.keepRunning() }
            }
        }
        rule.awaitIdle()

        assertThat(underTest.isActive).isTrue()
        assertThat(inspector.isActive).isTrue()
        assertThat(underTest.activeComputationCount).isEqualTo(1)

        keepRunning.value = false
        rule.awaitIdle()

        assertThat(underTest.isActive).isFalse()
        assertThat(inspector.isActive).isFalse()
        assertThat(underTest.activeComputationCount).isEqualTo(0)
    }
}
