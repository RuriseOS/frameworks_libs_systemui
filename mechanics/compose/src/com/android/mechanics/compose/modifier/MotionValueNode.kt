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

package com.android.mechanics.compose.modifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import com.android.mechanics.GestureContext
import com.android.mechanics.MotionValue
import com.android.mechanics.MotionValue.Companion.StableThresholdEffect
import com.android.mechanics.debug.MotionValueDebugger
import com.android.mechanics.debug.findMotionValueDebugger
import com.android.mechanics.spec.MotionSpec
import kotlinx.coroutines.launch

/**
 * A [Modifier.Node] that encapsulates a [MotionValue] and its lifecycle.
 *
 * This node observes an [input] value and drives a [MotionValue] animation based on a [MotionSpec].
 * It handles the creation, running, and cleanup of the motion value instance.
 *
 * Note: This is primarily intended to be used via the [DelegatingNode.delegate] function.
 *
 * @param input A lambda that provides the current input value for the motion.
 * @param gestureContext The context for gesture-driven animations.
 * @param initialSpec The initial [MotionSpec] to configure the animation.
 * @param label An optional label for debugging purposes.
 * @param stableThreshold The threshold to determine if the motion value is stable.
 * @param debug Whether this value needs to be registered to a [MotionValueDebugger].
 */
internal class MotionValueNode(
    private var input: () -> Float,
    gestureContext: GestureContext,
    initialSpec: MotionSpec = MotionSpec.Empty,
    label: String? = null,
    stableThreshold: Float = StableThresholdEffect,
    private val debug: Boolean = false,
) : Modifier.Node(), ObserverModifierNode {
    private var currentInputState by mutableFloatStateOf(input())

    private val motionValue =
        MotionValue(
            currentInput = { currentInputState },
            gestureContext = gestureContext,
            initialSpec = initialSpec,
            label = label,
            stableThreshold = stableThreshold,
        )

    /**
     * Whether the output value of the [motionValue] is currently fixed.
     *
     * This is true if the animation is at rest and the current input maps to a fixed output that
     * has not changed, which can be used to prevent unnecessary recompositions or layouts.
     */
    var isOutputFixed by mutableStateOf(motionValue.isOutputFixed)
        private set

    var output by mutableFloatStateOf(motionValue.output)
        private set

    override fun onAttach() {
        onObservedReadsChanged()

        coroutineScope.launch {
            val disposableHandle =
                if (debug) {
                    findMotionValueDebugger()?.register(motionValue)
                } else {
                    null
                }
            try {
                motionValue.keepRunning()
            } finally {
                disposableHandle?.dispose()
            }
        }
    }

    /**
     * This function is called by Compose whenever a state object that was read inside the
     * `observeReads` block has changed.
     *
     * Note: that this callback may not be invoked immediately, but can be deferred until a later
     * stage, such as after the measure and layout pass.
     */
    override fun onObservedReadsChanged() {
        observeReads { updateStates() }
    }

    /** Reads the latest input and updates the internal states of this node. */
    private fun updateStates() {
        currentInputState = input()
        isOutputFixed = motionValue.isOutputFixed

        // Only invoke the update callback if the output might have changed.
        if (isOutputFixed) return

        output = motionValue.output
    }

    fun updateSpec(spec: MotionSpec) {
        motionValue.spec = spec
    }
}
