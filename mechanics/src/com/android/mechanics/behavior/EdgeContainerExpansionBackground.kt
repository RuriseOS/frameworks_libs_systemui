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

package com.android.mechanics.behavior

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import kotlin.math.min

/**
 * Draws the background of an edge container, and applies clipping to it.
 *
 * Intended to be used with a [EdgeContainerExpansionSpec] motion.
 */
fun Modifier.edgeContainerExpansionBackground(
    backgroundColor: Color,
    spec: EdgeContainerExpansionSpec,
): Modifier = this.then(EdgeContainerExpansionBackgroundElement(backgroundColor, spec))

internal class EdgeContainerExpansionBackgroundNode(
    var backgroundColor: Color,
    var spec: EdgeContainerExpansionSpec,
) : Modifier.Node(), DrawModifierNode {

    private var graphicsLayer: GraphicsLayer? = null
    private var lastOutlineSize = Size.Zero

    fun invalidateOutline() {
        lastOutlineSize = Size.Zero
    }

    override fun onAttach() {
        graphicsLayer = requireGraphicsContext().createGraphicsLayer().apply { clip = true }
    }

    override fun onDetach() {
        requireGraphicsContext().releaseGraphicsLayer(checkNotNull(graphicsLayer))
    }

    override fun ContentDrawScope.draw() {
        val height = size.height

        // The width is growing between visibleHeight and detachHeight
        val visibleHeight = spec.visibleHeight.toPx()
        val widthFraction =
            ((height - visibleHeight) / (spec.detachHeight.toPx() - visibleHeight)).fastCoerceIn(
                0f,
                1f,
            )
        val width = size.width - lerp(spec.widthOffset.toPx(), 0f, widthFraction)
        val horizontalInset = (size.width - width) / 2f

        // The radius is growing at the beginning of the transition
        val radius = height.fastCoerceIn(spec.minRadius.toPx(), spec.radius.toPx())

        // Draw (at most) the bottom half of the rounded corner rectangle, aligned to the bottom.
        val upperHeight = height - radius

        // The rounded rect is drawn at 2x the radius height, to avoid smaller corner radii.
        // The clipRect limits this to the relevant part (-1 to avoid a hairline gap being visible
        // between this and the fill below.
        clipRect(top = (upperHeight - 1).fastCoerceAtLeast(0f)) {
            drawRoundRect(
                color = backgroundColor,
                cornerRadius = CornerRadius(radius),
                size = Size(width, radius * 2f),
                topLeft = Offset(horizontalInset, size.height - radius * 2f),
            )
        }

        if (upperHeight > 0) {
            // Fill the space above the bottom shape.
            drawRect(
                color = backgroundColor,
                topLeft = Offset(horizontalInset, 0f),
                size = Size(width, upperHeight),
            )
        }

        // Draw the node's content in a separate layer.
        val graphicsLayer = checkNotNull(graphicsLayer)
        graphicsLayer.record { this@draw.drawContent() }

        if (size != lastOutlineSize) {
            // The clip outline is a rounded corner shape matching the bottom of the shape.
            // At the top, the rounded corner shape extends by radiusPx above top.
            // This clipping thus would not prevent the containers content to overdraw at the top,
            // however this is off-screen anyways.
            val top = min(-radius, height - radius * 2f)

            val rect = Rect(left = horizontalInset, top = top, right = width, bottom = height)
            graphicsLayer.setRoundRectOutline(rect.topLeft, rect.size, radius)
            lastOutlineSize = size
        }

        this.drawLayer(graphicsLayer)
    }
}

private data class EdgeContainerExpansionBackgroundElement(
    val backgroundColor: Color,
    val spec: EdgeContainerExpansionSpec,
) : ModifierNodeElement<EdgeContainerExpansionBackgroundNode>() {
    override fun create(): EdgeContainerExpansionBackgroundNode =
        EdgeContainerExpansionBackgroundNode(backgroundColor, spec)

    override fun update(node: EdgeContainerExpansionBackgroundNode) {
        node.backgroundColor = backgroundColor
        if (node.spec != spec) {
            node.spec = spec
            node.invalidateOutline()
        }
    }
}
