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

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtLeast
import com.android.mechanics.effects.FixedValue
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.builder.MotionBuilderContext
import com.android.mechanics.spec.builder.effectsMotionSpec
import com.android.mechanics.spec.builder.fixedEffectsValueSpec

/**
 * This component remains hidden until it reach its target height.
 *
 * TODO: Once b/413283893 is done, [motionBuilderContext] can be read internally via
 *   CompositionLocalConsumerModifierNode, instead of passing it.
 */
fun Modifier.verticalFadeContentReveal(
    motionBuilderContext: MotionBuilderContext,
    deltaY: Float = 0f,
    label: String? = null,
    debug: Boolean = false,
): Modifier =
    this then
        FadeContentRevealElement(
            motionBuilderContext = motionBuilderContext,
            deltaY = deltaY,
            label = label,
            debug = debug,
        )

private data class FadeContentRevealElement(
    val motionBuilderContext: MotionBuilderContext,
    val deltaY: Float,
    val label: String?,
    val debug: Boolean,
) : ModifierNodeElement<FadeContentRevealNode>() {
    override fun create(): FadeContentRevealNode =
        FadeContentRevealNode(
            motionBuilderContext = motionBuilderContext,
            deltaY = deltaY,
            label = label,
            debug = debug,
        )

    override fun update(node: FadeContentRevealNode) {
        node.update(motionBuilderContext = motionBuilderContext, deltaY = deltaY)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fadeContentReveal"
        properties["deltaY"] = deltaY
        properties["label"] = label
        properties["debug"] = debug
    }
}

private class FadeContentRevealNode(
    private var motionBuilderContext: MotionBuilderContext,
    deltaY: Float,
    private val label: String?,
    private val debug: Boolean,
) : DelegatingNode(), ApproachLayoutModifierNode {
    private var lookAheadHeight by mutableFloatStateOf(0f)
    private var layoutOffsetY by mutableFloatStateOf(0f)
    private var deltaY: Float by mutableFloatStateOf(deltaY)

    private lateinit var animatedApproachMeasurement: MotionDriver.AnimatedApproachMeasurement
    private lateinit var motionDriver: MotionDriver

    fun update(motionBuilderContext: MotionBuilderContext, deltaY: Float) {
        this.motionBuilderContext = motionBuilderContext
        this.deltaY = deltaY
    }

    override fun onAttach() {
        motionDriver = findMotionDriver()
        animatedApproachMeasurement =
            motionDriver.animatedApproachMeasurement(
                request = MotionDriver.RequestConstraints.MaxHeight,
                spec = derivedStateOf(::spec)::value,
                label = "FadeContentReveal(${label.orEmpty()})",
                debug = debug,
            )
    }

    override fun onDetach() {
        animatedApproachMeasurement.dispose()
    }

    private fun spec(): MotionSpec {
        if (lookAheadHeight == 0f) {
            // We cannot compute specs for height 0.
            return motionBuilderContext.fixedEffectsValueSpec(0f)
        }

        return when (motionDriver.verticalState) {
            MotionDriver.State.MinValue -> {
                motionBuilderContext.fixedEffectsValueSpec(0f)
            }
            MotionDriver.State.Transition -> {
                motionBuilderContext.effectsMotionSpec(Mapping.Zero) {
                    after(layoutOffsetY + lookAheadHeight + deltaY, FixedValue.One)
                }
            }
            MotionDriver.State.MaxValue -> {
                motionBuilderContext.fixedEffectsValueSpec(1f)
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        if (isLookingAhead) {
            lookAheadHeight = placeable.height.toFloat()
        }
        return layout(placeable.width, placeable.height) {
            if (isLookingAhead) {
                layoutOffsetY = with(motionDriver) { driverOffset() }.y
            }
            placeable.place(IntOffset.Zero)
        }
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        return animatedApproachMeasurement.inProgress
    }

    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        return measurable.measure(constraints).run {
            layout(width, height) {
                val revealAlpha = animatedApproachMeasurement.value.fastCoerceAtLeast(0f)
                if (revealAlpha < 1f) {
                    placeWithLayer(IntOffset.Zero) {
                        alpha = revealAlpha
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                } else {
                    place(IntOffset.Zero)
                }
            }
        }
    }
}
