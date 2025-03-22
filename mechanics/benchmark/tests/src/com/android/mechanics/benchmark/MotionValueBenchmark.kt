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

package com.android.mechanics.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.compose.runtime.mutableFloatStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.DistanceGestureContext
import com.android.mechanics.MotionValue
import com.android.mechanics.spec.InputDirection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Benchmark, which will execute on an Android device. Previous results: go/mm-microbenchmarks */
@RunWith(AndroidJUnit4::class)
class MotionValueBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    @Test
    fun createMotionValue() {
        val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 2f)
        val currentInput = { 0f }
        benchmarkRule.measureRepeated { MotionValue(currentInput, gestureContext) }
    }

    @Test
    fun changeInput_readOutput() {
        val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 2f)
        val a = mutableFloatStateOf(0f)
        val motionValue = MotionValue(a::floatValue, gestureContext)

        benchmarkRule.measureRepeated {
            runWithMeasurementDisabled { a.floatValue += 1f }
            motionValue.floatValue
        }
    }

    @Test
    fun readOutputMultipleTimes() {
        val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 2f)
        val a = mutableFloatStateOf(0f)
        val motionValue = MotionValue(a::floatValue, gestureContext)

        benchmarkRule.measureRepeated {
            runWithMeasurementDisabled {
                a.floatValue += 1f
                motionValue.output
            }
            motionValue.output
        }
    }

    @Test
    fun readOutputMultipleTimesMeasureAll() {
        val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 2f)
        val currentInput = mutableFloatStateOf(0f)
        val motionValue = MotionValue(currentInput::floatValue, gestureContext)

        benchmarkRule.measureRepeated {
            currentInput.floatValue += 1f
            motionValue.output
            motionValue.output
        }
    }
}
