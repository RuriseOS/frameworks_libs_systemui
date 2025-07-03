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
import androidx.compose.ui.node.DelegatingNode
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
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.mechanics.gestureContextOrDefault
import com.android.mechanics.effects.FixedValue
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.builder.MotionBuilderContext
import com.android.mechanics.spec.builder.directionalMotionSpec
import com.android.mechanics.spec.builder.effectsMotionSpec

/**
 * This component remains hidden until it reach its target height.
 *
 * TODO: Once b/413283893 is done, [motionBuilderContext] can be read internally via
 *   CompositionLocalConsumerModifierNode, instead of passing it.
 */
fun Modifier.verticalFadeContentReveal(
    contentScope: ContentScope,
    motionBuilderContext: MotionBuilderContext,
    container: ElementKey,
    deltaY: Float = 0f,
    label: String? = null,
    debug: Boolean = false,
): Modifier =
    this then
        FadeContentRevealElement(
            contentScope = contentScope,
            motionBuilderContext = motionBuilderContext,
            container = container,
            deltaY = deltaY,
            label = label,
            debug = debug,
        )

private data class FadeContentRevealElement(
    val contentScope: ContentScope,
    val motionBuilderContext: MotionBuilderContext,
    val container: ElementKey,
    val deltaY: Float,
    val label: String?,
    val debug: Boolean,
) : ModifierNodeElement<FadeContentRevealNode>() {
    override fun create(): FadeContentRevealNode =
        FadeContentRevealNode(
            contentScope = contentScope,
            motionBuilderContext = motionBuilderContext,
            container = container,
            deltaY = deltaY,
            label = label,
            debug = debug,
        )

    override fun update(node: FadeContentRevealNode) {
        node.update(
            contentScope = contentScope,
            motionBuilderContext = motionBuilderContext,
            container = container,
            deltaY = deltaY,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fadeContentReveal"
        properties["container"] = container
        properties["deltaY"] = deltaY
        properties["label"] = label
        properties["debug"] = debug
    }
}

private class FadeContentRevealNode(
    private var contentScope: ContentScope,
    private var motionBuilderContext: MotionBuilderContext,
    private var container: ElementKey,
    private var deltaY: Float,
    label: String?,
    debug: Boolean,
) : DelegatingNode(), ApproachLayoutModifierNode, ObserverModifierNode {

    private val motionValueNode: MotionValueNode =
        delegate(
            MotionValueNode(
                input = {
                    with(contentScope) {
                        val containerHeight =
                            container.lastSize(contentKey)?.height ?: return@MotionValueNode 0f
                        containerHeight + deltaY
                    }
                },
                gestureContext = contentScope.gestureContextOrDefault(),
                initialSpec = MotionSpec(directionalMotionSpec(Mapping.Zero)),
                label = "FadeContentReveal(${label.orEmpty()})",
                debug = debug,
            )
        )

    fun update(
        contentScope: ContentScope,
        motionBuilderContext: MotionBuilderContext,
        container: ElementKey,
        deltaY: Float,
    ) {
        this.contentScope = contentScope
        this.motionBuilderContext = motionBuilderContext
        this.container = container
        this.deltaY = deltaY
        updateMotionSpec(contentScope.layoutState.transitionState)
    }

    override fun onAttach() {
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads { updateMotionSpec(contentScope.layoutState.transitionState) }
    }

    private var targetBounds = Rect.Zero

    private fun updateMotionSpec(transitionState: TransitionState) {
        val height = targetBounds.height
        if (height == 0f) {
            // We cannot compute specs for height 0.
            motionValueNode.updateSpec(MotionSpec(directionalMotionSpec(Mapping.Fixed(0f))))
            return
        }

        motionValueNode.updateSpec(
            when (transitionState) {
                is TransitionState.Idle -> {
                    val containerMinHeight = 0
                    val overlays = transitionState.currentOverlays
                    val scene = transitionState.currentScene
                    // The content is revealed if its height exceeds the minimum container height.
                    val isRevealed =
                        with(contentScope) {
                            // Determine the target content's height, prioritizing overlays, then
                            // the current scene.
                            val targetSize =
                                overlays.firstNotNullOfOrNull { container.targetSize(it) }
                                    ?: container.targetSize(scene)
                            val targetHeight = targetSize?.height ?: 0
                            targetHeight > containerMinHeight
                        }
                    MotionSpec(directionalMotionSpec(Mapping.Fixed(if (isRevealed) 1f else 0f)))
                }

                is TransitionState.Transition -> {
                    motionBuilderContext.effectsMotionSpec(Mapping.Zero) {
                        after(targetBounds.bottom, FixedValue.One)
                    }
                }
            }
        )
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        return !motionValueNode.isOutputFixed
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
        return measurable.measure(constraints).run {
            layout(width, height) {
                val revealAlpha = motionValueNode.output.fastCoerceAtLeast(0f)
                if (revealAlpha < 1) {
                    placeWithLayer(IntOffset.Zero) {
                        alpha = revealAlpha.fastCoerceAtLeast(0f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                } else {
                    place(IntOffset.Zero)
                }
            }
        }
    }
}
