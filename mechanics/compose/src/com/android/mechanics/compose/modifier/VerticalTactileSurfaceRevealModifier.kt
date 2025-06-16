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

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntRect
import androidx.compose.ui.unit.toRect
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.mechanics.gestureContextOrDefault
import com.android.mechanics.MotionValue
import com.android.mechanics.debug.findMotionValueDebugger
import com.android.mechanics.effects.RevealOnThreshold
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.builder.MotionBuilderContext
import com.android.mechanics.spec.builder.directionalMotionSpec
import com.android.mechanics.spec.builder.spatialMotionSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * This component remains hidden until its target height meets a minimum threshold. At that point,
 * it reveals itself by animating its height from 0 to the current target height.
 *
 * TODO: Once b/413283893 is done, [motionBuilderContext] can be read internally via
 *   CompositionLocalConsumerModifierNode, instead of passing it.
 */
fun Modifier.verticalTactileSurfaceReveal(
    contentScope: ContentScope,
    motionBuilderContext: MotionBuilderContext,
    container: ElementKey,
    deltaY: Float = 0f,
    revealOnThreshold: RevealOnThreshold = DefaultRevealOnThreshold,
    label: String? = null,
    debug: Boolean = false,
): Modifier =
    this then
        VerticalTactileSurfaceRevealElement(
            contentScope = contentScope,
            motionBuilderContext = motionBuilderContext,
            container = container,
            deltaY = deltaY,
            revealOnThreshold = revealOnThreshold,
            label = label,
            debug = debug,
        )

private val DefaultRevealOnThreshold = RevealOnThreshold()

private data class VerticalTactileSurfaceRevealElement(
    val contentScope: ContentScope,
    val motionBuilderContext: MotionBuilderContext,
    val container: ElementKey,
    val deltaY: Float,
    val revealOnThreshold: RevealOnThreshold,
    val label: String?,
    val debug: Boolean,
) : ModifierNodeElement<VerticalTactileSurfaceRevealNode>() {
    override fun create(): VerticalTactileSurfaceRevealNode =
        VerticalTactileSurfaceRevealNode(
            contentScope = contentScope,
            motionBuilderContext = motionBuilderContext,
            container = container,
            deltaY = deltaY,
            revealOnThreshold = revealOnThreshold,
            label = label,
            debug = debug,
        )

    override fun update(node: VerticalTactileSurfaceRevealNode) {
        node.update(
            contentScope = contentScope,
            motionBuilderContext = motionBuilderContext,
            container = container,
            deltaY = deltaY,
            revealOnThreshold = revealOnThreshold,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tactileSurfaceReveal"
        properties["container"] = container
        properties["deltaY"] = deltaY
        properties["revealOnThreshold"] = revealOnThreshold
        properties["label"] = label
        properties["debug"] = debug
    }
}

private class VerticalTactileSurfaceRevealNode(
    private var contentScope: ContentScope,
    private var motionBuilderContext: MotionBuilderContext,
    private var container: ElementKey,
    private var deltaY: Float,
    private var revealOnThreshold: RevealOnThreshold,
    label: String?,
    private val debug: Boolean,
) : Modifier.Node(), ApproachLayoutModifierNode, ObserverModifierNode {

    private val motionValue =
        MotionValue(
            currentInput = {
                with(contentScope) {
                    val containerHeight =
                        container.lastSize(contentKey)?.height ?: return@MotionValue 0f
                    containerHeight + deltaY
                }
            },
            initialSpec = MotionSpec(directionalMotionSpec(Mapping.Zero)),
            gestureContext = contentScope.gestureContextOrDefault(),
            label = "TactileSurfaceReveal(${label.orEmpty()})",
            stableThreshold = MotionBuilderContext.StableThresholdSpatial,
        )

    fun update(
        contentScope: ContentScope,
        motionBuilderContext: MotionBuilderContext,
        container: ElementKey,
        deltaY: Float,
        revealOnThreshold: RevealOnThreshold,
    ) {
        this.contentScope = contentScope
        this.motionBuilderContext = motionBuilderContext
        this.container = container
        this.deltaY = deltaY
        this.revealOnThreshold = revealOnThreshold
        updateMotionSpec(contentScope.layoutState.transitionState)
    }

    private var motionValueJob: Job? = null

    override fun onAttach() {
        onObservedReadsChanged()

        motionValueJob =
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

    override fun onDetach() {
        motionValueJob?.cancel()
    }

    override fun onObservedReadsChanged() {
        observeReads { updateMotionSpec(contentScope.layoutState.transitionState) }
    }

    private var targetBounds = Rect.Zero
    private var isContentTransition = false

    private fun updateMotionSpec(transitionState: TransitionState) {
        isContentTransition = transitionState is TransitionState.Transition

        val height = targetBounds.height
        if (height == 0f) {
            // We cannot compute specs for height 0.
            motionValue.spec = MotionSpec(directionalMotionSpec(Mapping.Fixed(0f)))
            return
        }

        motionValue.spec =
            when (transitionState) {
                is TransitionState.Idle -> {
                    val containerMinHeight = 0
                    val currentScene = transitionState.currentScene
                    val isRevealed =
                        with(contentScope) {
                            val targetHeight = container.targetSize(currentScene)?.height ?: 0
                            targetHeight > containerMinHeight
                        }
                    MotionSpec(directionalMotionSpec(Mapping.Fixed(if (isRevealed) height else 0f)))
                }
                is TransitionState.Transition -> {
                    motionBuilderContext.spatialMotionSpec(Mapping.Zero) {
                        between(
                            start = targetBounds.top,
                            end = targetBounds.bottom,
                            effect = revealOnThreshold,
                        )
                    }
                }
            }
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        return isContentTransition || !motionValue.isStable
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val coordinates = coordinates
            if (isLookingAhead && coordinates != null) {
                val containerCoordinates =
                    with(contentScope) { container.targetCoordinates(contentKey)!! }
                val containerOffset = containerCoordinates.localPositionOf(coordinates)
                val bounds = coordinates.size.toIntRect().toRect().translate(containerOffset)
                if (targetBounds != bounds) {
                    targetBounds = bounds
                    updateMotionSpec(contentScope.layoutState.transitionState)
                }
            }
            placeable.place(IntOffset.Zero)
        }
    }

    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val height = motionValue.output.roundToInt().fastCoerceAtLeast(0)
        val animatedConstraints = constraints.copy(maxHeight = height)
        return measurable.measure(animatedConstraints).run {
            layout(width, height) {
                val revealAlpha = (height / revealOnThreshold.minSize.toPx()).fastCoerceIn(0f, 1f)
                if (revealAlpha < 1) {
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
