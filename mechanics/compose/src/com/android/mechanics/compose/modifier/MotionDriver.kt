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

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.fastForEach
import com.android.mechanics.GestureContext
import com.android.mechanics.MotionValue
import com.android.mechanics.MotionValue.Companion.StableThresholdEffect
import com.android.mechanics.compose.modifier.MotionDriver.RequestConstraints
import com.android.mechanics.debug.findMotionValueDebugger
import com.android.mechanics.spec.MotionSpec
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

private const val TRAVERSAL_NODE_KEY = "MotionDriverNode"

/** Finds the nearest [MotionDriver] (or null) that was registered via a [motionDriver] modifier. */
private fun DelegatableNode.findMotionDriverOrNull(): MotionDriver? {
    return findNearestAncestor(TRAVERSAL_NODE_KEY) as? MotionDriver
}

/** Finds the nearest [MotionDriver] that was registered via a [motionDriver] modifier. */
internal fun DelegatableNode.findMotionDriver(): MotionDriver {
    return checkNotNull(findMotionDriverOrNull()) {
        "Did you forget to add the `motionDriver()` modifier to a parent Composable?"
    }
}

/**
 * A central interface for driving animations based on layout constraints.
 *
 * A `MotionDriver` is attached to a specific layout node using the [motionDriver] modifier.
 * Descendant nodes can find this driver to create animations whose target values are derived from
 * the driver's layout `Constraints`. It also provides access to the layout's geometry and a shared
 * [GestureContext].
 *
 * This allows for coordinated animations within a component tree that react to changes in a
 * parent's size, such as expanding or collapsing.
 */
internal interface MotionDriver {
    /** The [GestureContext] associated with this motion. */
    val gestureContext: GestureContext

    /**
     * The current vertical state of the layout, indicating if it's minimized, maximized, or in
     * transition.
     */
    val verticalState: State

    enum class State {
        MinValue,
        Transition,
        MaxValue,
    }

    /**
     * Calculates the positional offset from the `MotionDriver`'s layout to the current layout.
     *
     * This function should be called from within a `Placeable.PlacementScope` (such as a `layout`
     * block) by a descendant of the `motionDriver` modifier. It's useful for determining the
     * descendant's position relative to the driver's coordinate system, which can then be used as
     * an input for animations or other positional logic.
     *
     * @return The [Offset] of the current layout within the `MotionDriver`'s coordinate space.
     */
    fun Placeable.PlacementScope.driverOffset(): Offset

    /**
     * Creates and registers a [AnimatedApproachMeasurement] that animates based on layout
     * constraints.
     *
     * The returned value will automatically update its output whenever the `MotionDriver`'s layout
     * constraints change.
     *
     * @param request Defines how to extract a `Float` input value from the incoming `Constraints`.
     * @param spec A factory for the [MotionSpec] that governs the animation.
     * @param label A string identifier for debugging purposes.
     * @param stableThreshold The threshold (in pixels) at which the value is considered stable.
     * @return An [AnimatedApproachMeasurement] that provides the animated output.
     */
    fun animatedApproachMeasurement(
        request: RequestConstraints,
        spec: () -> MotionSpec,
        label: String? = null,
        stableThreshold: Float = StableThresholdEffect,
        debug: Boolean = false,
    ): AnimatedApproachMeasurement

    /**
     * A functional interface that defines how to convert layout [Constraints] into a single `Float`
     * value, which serves as the input for a [AnimatedApproachMeasurement].
     */
    fun interface RequestConstraints {

        /**
         * Extracts a `Float` input from the given [constraints].
         *
         * @param constraints The layout constraints provided during the measurement pass.
         * @return The `Float` value to be used as the animation input.
         */
        fun constraintsToInput(constraints: Constraints): Float

        /**
         * A predefined [RequestConstraints] implementation that uses the `maxHeight` of the
         * constraints as the input value.
         */
        object MaxHeight : RequestConstraints {
            override fun constraintsToInput(constraints: Constraints): Float {
                return constraints.maxHeight.toFloat()
            }
        }
    }

    /**
     * Represents a value that is derived from layout constraints and animated by a [MotionSpec].
     *
     * This value is state-backed and can be read in composition or snapshot-aware contexts to
     * trigger recomposition or other effects when it changes.
     */
    interface AnimatedApproachMeasurement : DisposableHandle {
        val inProgress: Boolean

        val value: Float
    }
}

/**
 * Creates and registers a [MotionDriver] for this layout.
 *
 * This allows descendant modifiers or layouts to find this `MotionDriver` (using
 * [findMotionDriver]) and observe its state, which is derived from layout changes (e.g., expanding
 * or collapsing).
 *
 * @param gestureContext The [GestureContext] to be made available through this [MotionDriver].
 * @param label An optional label for debugging and inspector tooling.
 */
fun Modifier.motionDriver(gestureContext: GestureContext, label: String? = null): Modifier =
    this then MotionDriverElement(gestureContext = gestureContext, label = label)

private data class MotionDriverElement(val gestureContext: GestureContext, val label: String?) :
    ModifierNodeElement<MotionDriverNode>() {
    override fun create(): MotionDriverNode = MotionDriverNode(gestureContext = gestureContext)

    override fun update(node: MotionDriverNode) {
        node.update(gestureContext = gestureContext)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "motionDriver"
        properties["label"] = label
    }
}

private class MotionDriverNode(override var gestureContext: GestureContext) :
    Modifier.Node(), TraversableNode, LayoutModifierNode, MotionDriver {
    private val animatedValues = mutableListOf<AnimatedApproachMeasurementImpl>()
    private var driverCoordinates: LayoutCoordinates? = null
    private var lookAheadHeight: Int = 0

    override val traverseKey: Any = TRAVERSAL_NODE_KEY
    override var verticalState: MotionDriver.State by mutableStateOf(MotionDriver.State.MinValue)

    fun update(gestureContext: GestureContext) {
        this.gestureContext = gestureContext
    }

    override fun Placeable.PlacementScope.driverOffset(): Offset {
        val driverCoordinates = requireNotNull(driverCoordinates) { "" }
        val childCoordinates = requireNotNull(coordinates) { "" }
        return driverCoordinates.localPositionOf(childCoordinates)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)

        if (isLookingAhead) {
            // In the lookahead pass, we capture the target height of the layout.
            // This is assumed to be the max value that the layout will animate to.
            lookAheadHeight = placeable.height
        } else {
            verticalState =
                when (placeable.height) {
                    0 -> MotionDriver.State.MinValue
                    lookAheadHeight -> MotionDriver.State.MaxValue
                    else -> MotionDriver.State.Transition
                }

            animatedValues.fastForEach { it.updateInput(constraints) }
        }

        return layout(width = placeable.width, height = placeable.height) {
            driverCoordinates = coordinates
            placeable.place(IntOffset.Zero)
        }
    }

    override fun animatedApproachMeasurement(
        request: RequestConstraints,
        spec: () -> MotionSpec,
        label: String?,
        stableThreshold: Float,
        debug: Boolean,
    ): MotionDriver.AnimatedApproachMeasurement {
        val animatedApproachMeasurement =
            AnimatedApproachMeasurementImpl(
                request = request,
                gestureContext = gestureContext,
                spec = spec,
                label = label,
                stableThreshold = stableThreshold,
                onDispose = { animatedValues -= this },
            )
        animatedValues += animatedApproachMeasurement

        coroutineScope.launch {
            val disposableHandle =
                if (debug) {
                    findMotionValueDebugger()?.register(animatedApproachMeasurement.motionValue)
                } else {
                    null
                }
            try {
                animatedApproachMeasurement.keepRunningWhileObserved()
            } finally {
                disposableHandle?.dispose()
            }
        }

        coroutineScope.launch {
            while (true) {
                withFrameNanos { animatedApproachMeasurement.computeOutput() }
            }
        }

        return animatedApproachMeasurement
    }

    private class AnimatedApproachMeasurementImpl(
        private val request: RequestConstraints,
        gestureContext: GestureContext,
        spec: () -> MotionSpec,
        label: String?,
        stableThreshold: Float,
        private val onDispose: AnimatedApproachMeasurementImpl.() -> Unit,
    ) : MotionDriver.AnimatedApproachMeasurement {
        private var isObserved = true
        private var lastInput: Float? = null

        val motionValue: MotionValue =
            MotionValue(
                input = { lastInput ?: 0f },
                gestureContext = gestureContext,
                spec = derivedStateOf(spec)::value,
                label = label,
                stableThreshold = stableThreshold,
            )

        override var inProgress: Boolean by mutableStateOf(false)

        override var value: Float by mutableFloatStateOf(motionValue.output)

        fun updateInput(input: Constraints): Boolean {
            val newInput = request.constraintsToInput(input)
            val isNew = lastInput != newInput
            if (isNew) lastInput = newInput
            return isNew
        }

        fun computeOutput() {
            val currentOutput = motionValue.output
            if (currentOutput.isFinite()) {
                inProgress = value != currentOutput
                value = currentOutput
            }
        }

        override fun dispose() {
            isObserved = false
            onDispose()
        }

        suspend fun keepRunningWhileObserved() = motionValue.keepRunningWhile { isObserved }
    }
}
