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

package com.android.mechanics.impl

import android.util.Log
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastIsFinite
import androidx.compose.ui.util.lerp
import com.android.mechanics.MotionValue.Companion.TAG
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.SegmentData
import com.android.mechanics.spec.SemanticKey
import com.android.mechanics.spring.SpringState
import com.android.mechanics.spring.calculateUpdatedState

internal interface ComputeSegment : CurrentFrameInput, LastFrameState, StaticConfig {
    /**
     * The current segment, which defines the [Mapping] function used to transform the input to the
     * output.
     *
     * While both [spec] and [currentDirection] remain the same, and [currentInput] is within the
     * segment (see [SegmentData.isValidForInput]), this is [lastSegment].
     *
     * Otherwise, [MotionSpec.onChangeSegment] is queried for an up-dated segment.
     */
    fun computeCurrentSegment(): SegmentData {
        val lastSegment = lastSegment
        val input = currentInput
        val direction = currentDirection

        val specChanged = lastSegment.spec != spec
        return if (specChanged || !lastSegment.isValidForInput(input, direction)) {
            spec.onChangeSegment(lastSegment, input, direction)
        } else {
            lastSegment
        }
    }
}

internal interface ComputeGuaranteeState : ComputeSegment {
    val currentSegment: SegmentData

    /** Computes the [SegmentChangeType] between [lastSegment] and [currentSegment]. */
    val segmentChangeType: SegmentChangeType
        get() {
            val currentSegment = currentSegment
            val lastSegment = lastSegment

            if (currentSegment.key == lastSegment.key) {
                return SegmentChangeType.Same
            }

            if (
                currentSegment.key.minBreakpoint == lastSegment.key.minBreakpoint &&
                    currentSegment.key.maxBreakpoint == lastSegment.key.maxBreakpoint
            ) {
                return SegmentChangeType.SameOppositeDirection
            }

            val currentSpec = currentSegment.spec
            val lastSpec = lastSegment.spec
            if (currentSpec !== lastSpec) {
                // Determine/guess whether the segment change was due to the changed spec, or
                // whether lastSpec would return the same segment key for the update input.
                val lastSpecSegmentForSameInput =
                    lastSpec.segmentAtInput(currentInput, currentDirection).key
                if (currentSegment.key != lastSpecSegmentForSameInput) {
                    // Note: this might not be correct if the new [MotionSpec.segmentHandlers] were
                    // involved.
                    return SegmentChangeType.Spec
                }
            }

            return if (currentSegment.direction == lastSegment.direction) {
                SegmentChangeType.Traverse
            } else {
                SegmentChangeType.Direction
            }
        }

    /**
     * Computes the fraction of [position] between [lastInput] and [currentInput].
     *
     * Essentially, this determines fractionally when [position] was crossed, between the current
     * frame and the last frame.
     *
     * Since frames are updated periodically, not continuously, crossing a breakpoint happened
     * sometime between the last frame's start and this frame's start.
     *
     * This fraction is used to estimate the time when a breakpoint was crossed since last frame,
     * and simplifies the logic of crossing multiple breakpoints in one frame, as it offers the
     * springs and guarantees time to be updated correctly.
     *
     * Of course, this is a simplification that assumes the input velocity was uniform during the
     * last frame, but that is likely good enough.
     */
    fun lastFrameFractionOfPosition(position: Float): Float {
        return ((position - lastInput) / (currentInput - lastInput)).fastCoerceIn(0f, 1f)
    }

    /**
     * The [GuaranteeState] for [currentSegment].
     *
     * Without a segment change, this carries forward [lastGuaranteeState], adjusted to the new
     * input if needed.
     *
     * If a segment change happened, this is a new [GuaranteeState] for the [currentSegment]. Any
     * remaining [lastGuaranteeState] will be consumed in [currentAnimation].
     */
    fun computeCurrentGuaranteeState(): GuaranteeState {
        val currentSegment = currentSegment
        val entryBreakpoint = currentSegment.entryBreakpoint

        // First, determine the origin of the guarantee computations
        val guaranteeOriginState =
            when (segmentChangeType) {
                // Still in the segment, the origin is carried over from the last frame
                SegmentChangeType.Same -> lastGuaranteeState
                // The direction changed within the same segment, no guarantee to enforce.
                SegmentChangeType.SameOppositeDirection -> return GuaranteeState.Inactive
                // The spec changes, there is no guarantee associated with the animation.
                SegmentChangeType.Spec -> return GuaranteeState.Inactive
                SegmentChangeType.Direction -> {
                    // Direction changed over a segment boundary. To make up for the
                    // directionChangeSlop, the guarantee starts at the current input.
                    GuaranteeState.withStartValue(
                        when (entryBreakpoint.guarantee) {
                            is Guarantee.InputDelta -> currentInput
                            is Guarantee.GestureDragDelta -> currentGestureDragOffset
                            is Guarantee.None -> return GuaranteeState.Inactive
                        }
                    )
                }

                SegmentChangeType.Traverse -> {
                    // Traversed over a segment boundary, the guarantee going forward is determined
                    // by the [entryBreakpoint].
                    GuaranteeState.withStartValue(
                        when (entryBreakpoint.guarantee) {
                            is Guarantee.InputDelta -> entryBreakpoint.position
                            is Guarantee.GestureDragDelta -> {
                                // Guess the GestureDragDelta origin - since the gesture dragOffset
                                // is sampled, interpolate it according to when the breakpoint was
                                // crossed in the last frame.
                                val fractionalBreakpointPos =
                                    lastFrameFractionOfPosition(entryBreakpoint.position)

                                lerp(
                                    lastGestureDragOffset,
                                    currentGestureDragOffset,
                                    fractionalBreakpointPos,
                                )
                            }

                            // No guarantee to enforce.
                            is Guarantee.None -> return GuaranteeState.Inactive
                        }
                    )
                }
            }

        // Finally, update the origin state with the current guarantee value.
        return guaranteeOriginState.withCurrentValue(
            when (entryBreakpoint.guarantee) {
                is Guarantee.InputDelta -> currentInput
                is Guarantee.GestureDragDelta -> currentGestureDragOffset
                is Guarantee.None -> return GuaranteeState.Inactive
            },
            currentSegment.direction,
        )
    }
}

internal interface ComputeAnimation : ComputeGuaranteeState {
    val currentGuaranteeState: GuaranteeState

    /**
     * The [DiscontinuityAnimation] in effect for the current frame.
     *
     * This describes the starting condition of the spring animation, and is only updated if the
     * spring animation must restarted: that is, if yet another discontinuity must be animated as a
     * result of a segment change, or if the [currentGuaranteeState] requires the spring to be
     * tightened.
     *
     * See [currentSpringState] for the continuously updated, animated spring values.
     */
    fun computeCurrentAnimation(): DiscontinuityAnimation {
        val currentSegment = currentSegment
        val lastSegment = lastSegment
        val currentSpec = spec
        val currentInput = currentInput
        val lastAnimation = lastAnimation

        return when (segmentChangeType) {
            SegmentChangeType.Same -> {
                if (lastAnimation.isAtRest) {
                    // Nothing to update if no animation is ongoing
                    lastAnimation
                } else if (lastGuaranteeState == currentGuaranteeState) {
                    // Nothing to update if the spring must not be tightened.
                    lastAnimation
                } else {
                    // Compute the updated spring parameters
                    val tightenedSpringParameters =
                        currentGuaranteeState.updatedSpringParameters(
                            currentSegment.entryBreakpoint
                        )

                    lastAnimation.copy(
                        springStartState = lastSpringState,
                        springParameters = tightenedSpringParameters,
                        springStartTimeNanos = lastFrameTimeNanos,
                    )
                }
            }

            SegmentChangeType.SameOppositeDirection,
            SegmentChangeType.Direction,
            SegmentChangeType.Spec -> {
                // Determine the delta in the output, as produced by the old and new mapping.
                val currentMapping = currentSegment.mapping.map(currentInput)
                val lastMapping = lastSegment.mapping.map(currentInput)
                val delta = currentMapping - lastMapping

                val deltaIsFinite = delta.fastIsFinite()
                if (!deltaIsFinite) {
                    Log.wtf(
                        TAG,
                        "Delta between mappings is undefined!\n" +
                            "  MotionValue: $label\n" +
                            "  input: $currentInput\n" +
                            "  lastMapping: $lastMapping (lastSegment: $lastSegment)\n" +
                            "  currentMapping: $currentMapping (currentSegment: $currentSegment)",
                    )
                }

                if (delta == 0f || !deltaIsFinite) {
                    // Nothing new to animate.
                    lastAnimation
                } else {
                    val springParameters =
                        if (segmentChangeType == SegmentChangeType.Direction) {
                            currentSegment.entryBreakpoint.spring
                        } else {
                            currentSpec.resetSpring
                        }

                    val newTarget = delta - lastSpringState.displacement
                    DiscontinuityAnimation(
                        newTarget,
                        SpringState(-newTarget, lastSpringState.velocity + directMappedVelocity),
                        springParameters,
                        lastFrameTimeNanos,
                    )
                }
            }

            SegmentChangeType.Traverse -> {
                // Process all breakpoints traversed, in order.
                // This is involved due to the guarantees - they have to be applied, one after the
                // other, before crossing the next breakpoint.
                val currentDirection = currentSegment.direction

                with(currentSpec[currentDirection]) {
                    val targetIndex = findSegmentIndex(currentSegment.key)
                    val sourceIndex = findSegmentIndex(lastSegment.key)
                    check(targetIndex != sourceIndex)

                    val directionOffset = if (targetIndex > sourceIndex) 1 else -1

                    var lastBreakpoint = lastSegment.entryBreakpoint
                    var lastAnimationTime = lastFrameTimeNanos
                    var guaranteeState = lastGuaranteeState
                    var springState = lastSpringState
                    var springTarget = lastAnimation.targetValue
                    var springParameters = lastAnimation.springParameters

                    var segmentIndex = sourceIndex
                    while (segmentIndex != targetIndex) {
                        val nextBreakpoint =
                            breakpoints[segmentIndex + directionOffset.fastCoerceAtLeast(0)]

                        val nextBreakpointFrameFraction =
                            lastFrameFractionOfPosition(nextBreakpoint.position)

                        val nextBreakpointCrossTime =
                            lerp(
                                lastFrameTimeNanos,
                                currentAnimationTimeNanos,
                                nextBreakpointFrameFraction,
                            )
                        if (
                            guaranteeState != GuaranteeState.Inactive &&
                                springState != SpringState.AtRest
                        ) {
                            val guaranteeValueAtNextBreakpoint =
                                when (lastBreakpoint.guarantee) {
                                    is Guarantee.InputDelta -> nextBreakpoint.position
                                    is Guarantee.GestureDragDelta ->
                                        lerp(
                                            lastGestureDragOffset,
                                            currentGestureDragOffset,
                                            nextBreakpointFrameFraction,
                                        )

                                    is Guarantee.None ->
                                        error(
                                            "guaranteeState ($guaranteeState) is not Inactive, guarantee is missing"
                                        )
                                }

                            guaranteeState =
                                guaranteeState.withCurrentValue(
                                    guaranteeValueAtNextBreakpoint,
                                    currentDirection,
                                )

                            springParameters =
                                guaranteeState.updatedSpringParameters(lastBreakpoint)
                        }

                        springState =
                            springState.calculateUpdatedState(
                                nextBreakpointCrossTime - lastAnimationTime,
                                springParameters,
                            )
                        lastAnimationTime = nextBreakpointCrossTime

                        val mappingBefore = mappings[segmentIndex]
                        val beforeBreakpoint = mappingBefore.map(nextBreakpoint.position)
                        val mappingAfter = mappings[segmentIndex + directionOffset]
                        val afterBreakpoint = mappingAfter.map(nextBreakpoint.position)

                        val delta = afterBreakpoint - beforeBreakpoint
                        val deltaIsFinite = delta.fastIsFinite()
                        if (!deltaIsFinite) {
                            Log.wtf(
                                TAG,
                                "Delta between breakpoints is undefined!\n" +
                                    "  MotionValue: $label\n" +
                                    "  position: ${nextBreakpoint.position}\n" +
                                    "  before: $beforeBreakpoint (mapping: $mappingBefore)\n" +
                                    "  after: $afterBreakpoint (mapping: $mappingAfter)",
                            )
                        }

                        if (deltaIsFinite) {
                            springTarget += delta
                            springState = springState.nudge(displacementDelta = -delta)
                        }
                        segmentIndex += directionOffset
                        lastBreakpoint = nextBreakpoint
                        guaranteeState =
                            when (nextBreakpoint.guarantee) {
                                is Guarantee.InputDelta ->
                                    GuaranteeState.withStartValue(nextBreakpoint.position)

                                is Guarantee.GestureDragDelta ->
                                    GuaranteeState.withStartValue(
                                        lerp(
                                            lastGestureDragOffset,
                                            currentGestureDragOffset,
                                            nextBreakpointFrameFraction,
                                        )
                                    )

                                is Guarantee.None -> GuaranteeState.Inactive
                            }
                    }

                    if (springState.displacement != 0f) {
                        springState = springState.nudge(velocityDelta = directMappedVelocity)
                    }

                    val tightened =
                        currentGuaranteeState.updatedSpringParameters(
                            currentSegment.entryBreakpoint
                        )

                    DiscontinuityAnimation(springTarget, springState, tightened, lastAnimationTime)
                }
            }
        }
    }
}

internal interface ComputeSpringState : ComputeAnimation {
    val currentAnimation: DiscontinuityAnimation

    fun computeCurrentSpringState(): SpringState {
        with(currentAnimation) {
            if (isAtRest) return SpringState.AtRest

            val nanosSinceAnimationStart = currentAnimationTimeNanos - springStartTimeNanos
            val updatedSpringState =
                springStartState.calculateUpdatedState(nanosSinceAnimationStart, springParameters)

            return if (updatedSpringState.isStable(springParameters, stableThreshold)) {
                SpringState.AtRest
            } else {
                updatedSpringState
            }
        }
    }
}

internal interface Computations : ComputeSpringState {
    val currentSpringState: SpringState

    val currentDirectMapped: Float
        get() = currentSegment.mapping.map(currentInput) - currentAnimation.targetValue

    val currentAnimatedDelta: Float
        get() = currentAnimation.targetValue + currentSpringState.displacement

    val output: Float
        get() = currentDirectMapped + currentAnimatedDelta

    val outputTarget: Float
        get() = currentDirectMapped + currentAnimation.targetValue

    val isStable: Boolean
        get() = currentSpringState == SpringState.AtRest

    fun <T> semanticState(semanticKey: SemanticKey<T>): T? {
        return with(currentSegment) { spec.semanticState(semanticKey, key) }
    }
}
