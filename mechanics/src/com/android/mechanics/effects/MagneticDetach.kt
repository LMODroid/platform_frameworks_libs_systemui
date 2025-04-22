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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.mechanics.effects

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.ChangeSegmentHandlers.PreventDirectionChangeWithinCurrentSegment
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.SegmentKey
import com.android.mechanics.spec.SemanticKey
import com.android.mechanics.spec.builder.Effect
import com.android.mechanics.spec.builder.EffectApplyScope
import com.android.mechanics.spec.builder.EffectPlacement
import com.android.mechanics.spec.builder.MotionBuilderContext
import com.android.mechanics.spec.with
import com.android.mechanics.spring.SpringParameters

/**
 * Gesture effect that emulates effort to detach an element from its resting position.
 *
 * @param semanticState semantic state used to check the state of this effect.
 * @param detachPosition distance from the origin to detach
 * @param attachPosition distance from the origin to re-attach
 * @param detachScale fraction of input changes propagated during detach.
 * @param attachScale fraction of input changes propagated after re-attach.
 * @param detachSpring spring used during detach
 * @param attachSpring spring used during attach
 */
class MagneticDetach(
    private val semanticState: SemanticKey<State> = Defaults.AttachDetachState,
    private val detachPosition: Dp = Defaults.DetachPosition,
    private val attachPosition: Dp = Defaults.AttachPosition,
    private val detachScale: Float = Defaults.AttachDetachScale,
    private val attachScale: Float = Defaults.AttachDetachScale * (attachPosition / detachPosition),
    private val detachSpring: SpringParameters = Defaults.Spring,
    private val attachSpring: SpringParameters = Defaults.Spring,
) : Effect {

    init {
        require(attachPosition <= detachPosition)
    }

    enum class State {
        Attached,
        Detached,
    }

    override fun MotionBuilderContext.measure(effectPlacement: EffectPlacement): Float {
        return detachPosition.toPx() * effectPlacement.directionSign
    }

    override fun EffectApplyScope.createSpec() {
        val startPos = minLimit
        val reattachPos = startPos + attachPosition.toPx()
        val detachPos = maxLimit
        val startValue = baseValue(startPos)
        val detachValue = baseValue(detachPos)
        val reattachValue = baseValue(reattachPos)

        val scaledDetachValue = startValue + (detachValue - startValue) * detachScale
        val scaledReattachValue = startValue + (reattachValue - startValue) * attachScale

        val attachKey = BreakpointKey("attach")

        forward(
            initialMapping = Mapping.Linear(startPos, startValue, detachPos, scaledDetachValue),
            semantics = listOf(semanticState with State.Attached),
        ) {
            maxLimitSpring = detachSpring
            maxLimitSemantics = listOf(semanticState with State.Detached)
        }

        backward(
            initialMapping = Mapping.Linear(startPos, startValue, reattachPos, scaledReattachValue),
            semantics = listOf(semanticState with State.Attached),
        ) {
            mapping(
                breakpoint = reattachPos,
                key = attachKey,
                spring = attachSpring,
                semantics = listOf(semanticState with State.Detached),
                mapping = baseMapping,
            )
        }

        val beforeDetachSegment = SegmentKey(minLimitKey, maxLimitKey, InputDirection.Max)
        val beforeAttachSegment = SegmentKey(attachKey, maxLimitKey, InputDirection.Min)
        val afterAttachSegment = SegmentKey(minLimitKey, attachKey, InputDirection.Min)

        // Suppress direction change during detach. This prevents snapping to the origin when
        // changing the direction while detaching.
        addSegmentHandler(beforeDetachSegment, PreventDirectionChangeWithinCurrentSegment)
        // Suppress direction when approaching attach. This prevents the detach effect when changing
        // direction just before reattaching.
        addSegmentHandler(beforeAttachSegment, PreventDirectionChangeWithinCurrentSegment)

        // When changing direction after re-attaching, the pre-detach ratio is tweaked to
        // interpolate between the direction change-position and the detach point.
        addSegmentHandler(afterAttachSegment) { currentSegment, newInput, newDirection ->
            val nextSegment = segmentAtInput(newInput, newDirection)
            if (nextSegment.key == beforeDetachSegment) {

                val pivotPos = newInput
                val pivotValue = currentSegment.mapping.map(pivotPos)

                val tweakedMapping = Mapping { input ->
                    if (input <= pivotPos) {
                        val t = (input - startPos) / (pivotPos - startPos)
                        lerp(startValue, pivotValue, t)
                    } else {
                        val t = (input - pivotPos) / (detachPos - pivotPos)
                        lerp(pivotValue, scaledDetachValue, t)
                    }
                }
                nextSegment.copy(mapping = tweakedMapping)
            } else {
                nextSegment
            }
        }
    }

    companion object {
        object Defaults {
            val AttachDetachState = SemanticKey<State>()
            val AttachDetachScale = .3f
            val DetachPosition = 80.dp
            val AttachPosition = 40.dp
            val Spring = SpringParameters(stiffness = 800f, dampingRatio = 0.95f)
        }
    }
}
