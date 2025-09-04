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

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.util.trace
import androidx.compose.ui.util.traceValue
import com.android.mechanics.MotionValue.Companion.StableThresholdSpatial
import com.android.mechanics.debug.DebugInspector
import com.android.mechanics.debug.FrameData
import com.android.mechanics.impl.Computations
import com.android.mechanics.impl.DiscontinuityAnimation
import com.android.mechanics.impl.GuaranteeState
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.SegmentData
import com.android.mechanics.spec.SegmentKey
import com.android.mechanics.spec.SemanticKey
import com.android.mechanics.spring.SpringState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The type of MotionValue created by the [MotionValueCollection]. */
sealed interface ManagedMotionValue : MotionValueState, DisposableHandle

/**
 * A collection of motion values that all share the same input and gesture context.
 *
 * All [ManagedMotionValue]s are run from the same [keepRunning], and share the same lifecycle.
 *
 * Input, gesture context and spec are updated all at once, at the beginning of the, during
 * [withFrameNanos].
 */
class MotionValueCollection(
    internal val input: () -> Float,
    internal val gestureContext: GestureContext,
    internal val stableThreshold: Float = StableThresholdSpatial,
    val label: String? = null,
) {
    private val managedComputations = mutableStateSetOf<ManagedMotionComputation>()

    /**
     * Creates a new [ManagedMotionValue], whose output is controlled by [spec].
     *
     * The returned [ManagedMotionValue] must be disposed when not used anymore, while this
     * [MotionValueCollection] is kept active.
     */
    fun create(spec: () -> MotionSpec, label: String? = null): ManagedMotionValue {
        return ManagedMotionComputation(this, spec, label).also {
            if (isActive) {
                it.onActivate()
            }
            managedComputations.add(it)
        }
    }

    /**
     * Conditionally wraps the execution of a [block] in a performance trace.
     *
     * The primary advantage of this helper is lazy evaluation. The trace message from
     * [onTraceStart] is not computed and no `try-finally` block is entered unless tracing is
     * [enabled]. This helps to avoid performance penalties in production builds where tracing is
     * often turned off.
     *
     * @param enabled A boolean flag to enable or disable tracing.
     * @param onTraceStart A lambda that returns the trace section name. Only invoked if [enabled]
     *   is true.
     * @param onTraceEnd A lambda that executes after the block has finished. Only invoked if
     *   [enabled] is true.
     * @param block The code block to be executed and traced.
     */
    private inline fun trace(
        enabled: Boolean,
        onTraceStart: () -> String,
        onTraceEnd: (Duration) -> Unit = {},
        block: () -> Unit,
    ) {
        if (enabled) {
            val duration = measureTime { trace(onTraceStart(), block) }

            onTraceEnd(duration)
        } else {
            block()
        }
    }

    /**
     * Keeps the all created [ManagedMotionValue]'s animated output running.
     *
     * Clients must call [keepRunning], and keep the coroutine running while any of the created
     * [ManagedMotionValue] is in use. Cancel the coroutine if no values are being used anymore.
     *
     * Internally, this method does suspend, unless there are animations ongoing.
     */
    suspend fun keepRunning(): Nothing {
        withContext(CoroutineName("MotionValueCollection($label)")) {
            check(!isActive) { "MotionValueCollection($label) is already running" }
            isActive = true

            // These `captured*` values will be applied to the `last*` values, at the beginning
            // of the each new frame.
            // TODO(b/397837971): Encapsulate the state in a StateRecord.
            // TODO(b/397837971): last/current values could all be updated at the beginning of the
            // frame, when latching.
            var capturedFrameTimeNanos = currentAnimationTimeNanos
            var capturedInput = currentInput
            var capturedGestureDragOffset = currentGestureDragOffset
            var capturedDirection = currentDirection

            managedComputations.forEach { it.onActivate() }

            try {
                isAnimating = true

                // indicates whether withFrameNanos is called continuously (as opposed to being
                // suspended for an undetermined amount of time in between withFrameNanos).
                // This is essential after `withFrameNanos` returned: if true at this point,
                // currentAnimationTimeNanos - lastFrameNanos is the duration of the last frame.
                var isAnimatingUninterrupted = false

                while (true) {

                    withFrameNanos { frameTimeNanos ->
                        frameCount++

                        trace(
                            enabled = isTraceEnabled,
                            onTraceStart = {
                                val prefix = "MotionValueCollection($label)"
                                val unstable = managedComputations.count { !it.isStable }
                                val all = managedComputations.size
                                traceValue("$prefix:unstable", unstable.toLong())
                                traceValue("$prefix:all", all.toLong())

                                "$prefix withFrameNanos f:$frameCount ($unstable/$all)"
                            },
                            onTraceEnd = {
                                val prefix = "MotionValueCollection($label)"
                                traceValue("$prefix:duration", it.inWholeMicroseconds)
                            },
                        ) {
                            currentAnimationTimeNanos = frameTimeNanos
                            lastFrameTimeNanos = capturedFrameTimeNanos
                            lastInput = capturedInput
                            lastGestureDragOffset = capturedGestureDragOffset

                            currentInput = input.invoke()
                            currentDirection = gestureContext.direction
                            currentGestureDragOffset = gestureContext.dragOffset

                            managedComputations.forEach { it.onFrameStart() }
                        }
                    }

                    // At this point, the complete frame is done (including layout, drawing and
                    // everything else), and this MotionValue has been updated.

                    // Capture the `current*` MotionValue state, so that it can be applied as the
                    // `last*` state when the next frame starts. Its imperative to capture at this
                    // point
                    // already (since the input could change before the next frame starts), while at
                    // the
                    // same time not already applying the `last*` state (as this would cause a
                    // re-computation if the current state is being read before the next frame).

                    var scheduleNextFrame = false
                    managedComputations.forEach {
                        if (it.onFrameEnd(isAnimatingUninterrupted)) {
                            scheduleNextFrame = true
                        }
                    }

                    if (capturedInput != currentInput) {
                        capturedInput = currentInput
                        scheduleNextFrame = true
                    }

                    if (capturedGestureDragOffset != currentGestureDragOffset) {
                        capturedGestureDragOffset = currentGestureDragOffset
                        scheduleNextFrame = true
                    }

                    if (capturedDirection != currentDirection) {
                        capturedDirection = currentDirection
                        scheduleNextFrame = true
                    }

                    capturedFrameTimeNanos = currentAnimationTimeNanos

                    isAnimatingUninterrupted = scheduleNextFrame
                    if (scheduleNextFrame) {
                        continue
                    }

                    isAnimating = false
                    managedComputations.forEach { it.debugInspector?.isAnimating = false }
                    val activeComputations = managedComputations.toSet()

                    snapshotFlow {
                            val hasComputations =
                                activeComputations.isNotEmpty() || managedComputations.isNotEmpty()

                            val wakeup =
                                hasComputations &&
                                    (activeComputations != managedComputations ||
                                        activeComputations.any { it.wantWakeup() } ||
                                        input.invoke() != capturedInput ||
                                        gestureContext.direction != capturedDirection ||
                                        gestureContext.dragOffset != capturedGestureDragOffset)
                            wakeup
                        }
                        .first { it }
                    isAnimating = true
                    managedComputations.forEach { it.debugInspector?.isAnimating = true }
                }
            } finally {
                isActive = false
                managedComputations.forEach { it.onDeactivate() }
            }
        }
    }

    // ---- Implementation - State shared with all ManagedMotionComputations  ----------------------
    // Note that all this state is updated exactly once per frame, during [withFrameNanos].
    internal var currentAnimationTimeNanos by mutableLongStateOf(-1L)

    @VisibleForTesting
    var currentInput: Float by mutableFloatStateOf(input.invoke())
        private set

    @VisibleForTesting
    var currentDirection: InputDirection by mutableStateOf(gestureContext.direction)
        private set

    @VisibleForTesting
    var currentGestureDragOffset: Float by mutableFloatStateOf(gestureContext.dragOffset)
        private set

    internal var lastFrameTimeNanos by mutableLongStateOf(-1L)
    internal var lastInput by mutableFloatStateOf(currentInput)
    internal var lastGestureDragOffset by mutableFloatStateOf(currentGestureDragOffset)

    // ---- Testing related state ------------------------------------------------------------------

    @VisibleForTesting
    var isActive = false
        private set

    @VisibleForTesting
    var isAnimating = false
        private set

    @VisibleForTesting
    var frameCount = 0
        private set

    @VisibleForTesting
    // Note - this is public so that its accessible by the mechanics:testing library
    val managedMotionValues: Set<ManagedMotionValue>
        get() = managedComputations

    internal fun onDispose(toDispose: ManagedMotionComputation) {
        managedComputations.remove(toDispose)
        toDispose.onDeactivate()
    }

    companion object {
        var isTraceEnabled: Boolean = false
    }
}

internal class ManagedMotionComputation(
    private val owner: MotionValueCollection,
    private val specProvider: () -> MotionSpec,
    override val label: String?,
) : Computations(), ManagedMotionValue {

    override val stableThreshold: Float
        get() = owner.stableThreshold

    // ----  ManagedMotionValue --------------------------------------------------------------------

    override var output: Float by mutableFloatStateOf(Float.NaN)

    /**
     * [output] value, but without animations.
     *
     * This value always reports the target value, even before a animation is finished.
     *
     * While [isStable], [outputTarget] and [output] are the same value.
     */
    override var outputTarget: Float by mutableFloatStateOf(Float.NaN)

    /** Whether an animation is currently running. */
    override var isStable: Boolean by mutableStateOf(false)

    override val spec
        get() = specProvider.invoke()

    override fun <T> get(key: SemanticKey<T>): T? = computedSemanticState(key)

    override val segmentKey: SegmentKey
        get() = currentComputedValues.segment.key

    override val floatValue: Float
        get() = output

    override fun dispose() {
        owner.onDispose(this)
    }

    override fun debugInspector(): DebugInspector {
        if (debugInspectorRefCount.getAndIncrement() == 0) {
            debugInspector =
                DebugInspector(
                    FrameData(
                        lastInput,
                        lastSegment.direction,
                        lastGestureDragOffset,
                        lastFrameTimeNanos,
                        lastSpringState,
                        lastSegment,
                        lastAnimation,
                        computedIsOutputFixed,
                    ),
                    owner.isActive,
                    owner.isAnimating,
                    ::onDisposeDebugInspector,
                )
        }

        return checkNotNull(debugInspector)
    }

    private var debugInspectorRefCount = AtomicInteger(0)

    private fun onDisposeDebugInspector() {
        if (debugInspectorRefCount.decrementAndGet() == 0) {
            debugInspector = null
        }
    }

    // ----  CurrentFrameInput ---------------------------------------------------------------------

    override val currentInput: Float
        get() = owner.currentInput

    override val currentDirection: InputDirection
        get() = owner.currentDirection

    override val currentGestureDragOffset: Float
        get() = owner.currentGestureDragOffset

    override val currentAnimationTimeNanos
        get() = owner.currentAnimationTimeNanos

    // ----  LastFrameState ---------------------------------------------------------------------

    override var lastSegment: SegmentData by
        mutableStateOf(
            this.spec.segmentAtInput(currentInput, currentDirection),
            referentialEqualityPolicy(),
        )

    override var lastGuaranteeState: GuaranteeState
        get() = GuaranteeState(_lastGuaranteeStatePacked)
        set(value) {
            _lastGuaranteeStatePacked = value.packedValue
        }

    private var _lastGuaranteeStatePacked: Long by
        mutableLongStateOf(GuaranteeState.Inactive.packedValue)

    override var lastAnimation: DiscontinuityAnimation by
        mutableStateOf(DiscontinuityAnimation.None, referentialEqualityPolicy())

    override var directMappedVelocity: Float = 0f

    override var lastSpringState: SpringState
        get() = SpringState(_lastSpringStatePacked)
        set(value) {
            _lastSpringStatePacked = value.packedValue
        }

    private var _lastSpringStatePacked: Long by
        mutableLongStateOf(lastAnimation.springStartState.packedValue)

    override val lastFrameTimeNanos
        get() = owner.lastFrameTimeNanos

    override val lastInput
        get() = owner.lastInput

    override val lastGestureDragOffset
        get() = owner.lastGestureDragOffset

    // ---- Computations ---------------------------------------------------------------------------

    var debugInspector: DebugInspector? = null

    // These `captured*` values will be applied to the `last*` values, at the beginning
    // of the each new frame.
    // TODO(b/397837971): Encapsulate the state in a StateRecord.
    var capturedSegment = currentComputedValues.segment
    var capturedGuaranteeState = currentComputedValues.guarantee
    var capturedAnimation = currentComputedValues.animation
    var capturedSpringState = currentSpringState

    fun onActivate() {
        val currentComputedValues = currentComputedValues
        capturedSegment = currentComputedValues.segment
        capturedGuaranteeState = currentComputedValues.guarantee
        capturedAnimation = currentComputedValues.animation
        capturedSpringState = currentSpringState

        onFrameStart()

        debugInspector?.isAnimating = true
        debugInspector?.isActive = true
    }

    fun onDeactivate() {
        debugInspector?.isAnimating = false
        debugInspector?.isActive = false
    }

    fun onFrameStart() {
        lastSegment = capturedSegment
        lastGuaranteeState = capturedGuaranteeState
        lastAnimation = capturedAnimation
        lastSpringState = capturedSpringState

        output = computedOutput
        outputTarget = computedOutputTarget
        isStable = computedIsStable
    }

    fun onFrameEnd(isAnimatingUninterrupted: Boolean): Boolean {
        directMappedVelocity =
            if (isAnimatingUninterrupted) {
                computeDirectMappedVelocity(currentAnimationTimeNanos - lastFrameTimeNanos)
            } else 0f

        var scheduleNextFrame = false
        if (!isSameSegmentAndAtRest) {
            // Read currentComputedValues only once and update it, if necessary
            val currentValues = currentComputedValues

            if (capturedSegment != currentValues.segment) {
                capturedSegment = currentValues.segment
                scheduleNextFrame = true
            }

            if (capturedGuaranteeState != currentValues.guarantee) {
                capturedGuaranteeState = currentValues.guarantee
                scheduleNextFrame = true
            }

            if (capturedAnimation != currentValues.animation) {
                capturedAnimation = currentValues.animation
                scheduleNextFrame = true
            }

            if (capturedSpringState != currentSpringState) {
                capturedSpringState = currentSpringState
                scheduleNextFrame = true
            }
        }

        debugInspector?.run {
            frame =
                FrameData(
                    currentInput,
                    currentDirection,
                    currentGestureDragOffset,
                    currentAnimationTimeNanos,
                    capturedSpringState,
                    capturedSegment,
                    capturedAnimation,
                    computedIsOutputFixed,
                )
        }

        return scheduleNextFrame
    }

    fun wantWakeup(): Boolean {
        return spec != capturedSegment.spec
    }
}
