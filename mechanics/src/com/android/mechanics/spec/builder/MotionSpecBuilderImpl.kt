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

package com.android.mechanics.spec.builder

import androidx.collection.MutableIntIntMap
import androidx.collection.MutableIntLongMap
import androidx.collection.MutableIntObjectMap
import androidx.collection.ObjectList
import androidx.collection.mutableObjectListOf
import com.android.mechanics.spec.Breakpoint
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.OnChangeSegmentHandler
import com.android.mechanics.spec.SegmentKey
import com.android.mechanics.spec.SemanticValue
import com.android.mechanics.spring.SpringParameters

internal class MotionSpecBuilderImpl(
    override val baseMapping: Mapping,
    override val defaultSpring: SpringParameters,
    val resetSpring: SpringParameters,
    val baseSemantics: List<SemanticValue<*>>,
    motionBuilderContext: MotionBuilderContext,
) : MotionSpecBuilderScope, MotionBuilderContext by motionBuilderContext, EffectApplyScope {

    private val placedEffects = MutableIntObjectMap<Effect>()
    private val absoluteEffectPlacements = MutableIntLongMap()
    private val relativeEffectPlacements = MutableIntIntMap()

    private lateinit var builders: ObjectList<DirectionalBuilderImpl>
    private val forwardBuilder: DirectionalBuilderImpl
        get() = builders[0]

    private val reverseBuilder: DirectionalBuilderImpl
        get() = builders[1]

    private lateinit var segmentHandlers: MutableMap<SegmentKey, OnChangeSegmentHandler>

    fun build(): MotionSpec {
        if (placedEffects.isEmpty()) {
            return MotionSpec(directionalMotionSpec(baseMapping), resetSpring = resetSpring)
        }

        builders =
            mutableObjectListOf(
                DirectionalBuilderImpl(defaultSpring, baseSemantics),
                DirectionalBuilderImpl(defaultSpring, baseSemantics),
            )
        segmentHandlers = mutableMapOf()

        val placedEffects = placeEffects()

        check(placedEffects.size >= 2)

        with(Breakpoint.minLimit) {
            minLimit = position
            minLimitKey = key
            minLimitSpring = spring
            minLimitGuarantee = guarantee
        }

        for (i in 0 until placedEffects.size - 1) {
            val (effect, placement) = placedEffects[i]

            maxLimit = placement.max
            maxLimitKey = BreakpointKey()
            maxLimitSpring = defaultSpring
            maxLimitGuarantee = Guarantee.None
            maxLimitSemantics = emptyList()

            applyEffect(effect)

            minLimit = maxLimit
            minLimitKey = maxLimitKey
            minLimitSpring = maxLimitSpring
            minLimitGuarantee = maxLimitGuarantee
        }

        val (lastDefinition, _) = placedEffects.last()

        with(Breakpoint.maxLimit) {
            maxLimit = position
            maxLimitKey = key
            maxLimitSpring = spring
            maxLimitGuarantee = guarantee
        }

        applyEffect(lastDefinition)

        return MotionSpec(
            builders[0].build(),
            builders[1].build(),
            resetSpring,
            segmentHandlers.toMap(),
        )
    }

    private fun placeEffects(): List<Pair<Effect, EffectPlacement>> {
        check(absoluteEffectPlacements.isNotEmpty())
        return buildList {
            fun placeEffect(placement: EffectPlacement, effect: Effect): EffectPlacement {
                val extent = with(effect) { measure(placement) }
                val actualPlacement =
                    EffectPlacement.between(placement.start, placement.start + extent)
                add(Pair(effect, actualPlacement))
                return actualPlacement
            }

            fun placeRelativeEffects(
                effect: PlacedEffect,
                direction: Int,
                relatedPlacement: EffectPlacement,
            ) {
                require(direction == -1 || direction == 1)

                val key = effect.id * direction
                if (!relativeEffectPlacements.containsKey(key)) return

                val relativePlacedEffect = PlacedEffect(relativeEffectPlacements[key])
                val effect = checkNotNull(placedEffects[relativePlacedEffect.id])

                val placement =
                    if (direction == -1) {
                        EffectPlacement.before(relatedPlacement.min)
                    } else {
                        EffectPlacement.after(relatedPlacement.max)
                    }

                val actualPlacement = placeEffect(placement, effect)
                placeRelativeEffects(relativePlacedEffect, direction, actualPlacement)
            }

            // Start with placing all absolute positioned effects, as well as the relatively
            // positioned ones.
            absoluteEffectPlacements.forEach { id, placement ->
                val effect = checkNotNull(placedEffects[id])
                val actualPlacement = placeEffect(EffectPlacement(placement), effect)
                placeRelativeEffects(PlacedEffect(id), 1, actualPlacement)
                placeRelativeEffects(PlacedEffect(id), -1, actualPlacement)
            }

            // By now, all effects have been placed. The placements can still be unbounded.
            check(size == placedEffects.size) { "Some effects were not placed." }
            // Sort all effects by their ascending placement.
            sortBy { it.second.sortOrder }

            // In a second pass, do the following:
            // - update all placements to extend to their neighbor
            // - verify that no segments overlap
            // - add `EmptyPlaceholder` effects to ensure the complete input space is covered by
            //   an effect. This will make adding the effects simpler

            // EmptyPlaceholder will be added to the end of the list, an additional sort at the end
            // will bring them in line again. This prevents insert operations during the second
            // pass.
            val (_, firstPlacement) = first()
            val (_, lastPlacement) = last()

            for (i in 0 until size - 1) {
                val (thisDefinition, thisPlacement) = get(i)
                val (nextDefinition, nextPlacement) = get(i + 1)

                when {
                    thisPlacement.max == Float.POSITIVE_INFINITY &&
                        nextPlacement.min == Float.NEGATIVE_INFINITY -> {
                        throw IllegalStateException(
                            "Only one of the effects can extend to the  boundary, not both:\n" +
                                "  this:  $thisPlacement ($thisDefinition)\n" +
                                "  next:  $nextPlacement ($nextDefinition)\n"
                        )
                    }
                    thisPlacement.max == Float.POSITIVE_INFINITY -> {
                        val updated = EffectPlacement.between(thisPlacement.min, nextPlacement.min)
                        set(i, thisDefinition to updated)
                    }

                    nextPlacement.min == Float.NEGATIVE_INFINITY -> {
                        val updated = EffectPlacement.between(thisPlacement.max, nextPlacement.max)
                        set(i + 1, nextDefinition to updated)
                    }

                    thisPlacement.max < nextPlacement.min -> {
                        add(
                            EmptyPlaceholder to
                                EffectPlacement.between(thisPlacement.max, nextPlacement.min)
                        )
                    }
                    else -> {
                        check(thisPlacement.max == nextPlacement.min) {
                            "Effects must not overlap:\n" +
                                "  this:  $thisPlacement ($thisDefinition)\n" +
                                "  next:  $nextPlacement ($nextDefinition)\n"
                        }
                        require(thisPlacement.max.isFinite() && nextPlacement.min.isFinite())
                    }
                }
            }

            if (firstPlacement.min != Float.NEGATIVE_INFINITY) {
                require(firstPlacement.min.isFinite())
                add(EmptyPlaceholder to EffectPlacement.before(firstPlacement.min))
            }

            if (lastPlacement.max != Float.POSITIVE_INFINITY) {
                require(lastPlacement.max.isFinite())
                add(EmptyPlaceholder to EffectPlacement.after(lastPlacement.max))
            }

            sortBy { it.second.sortOrder }
        }
    }

    // ---- MotionSpecBuilderScope implementation --------------------------------------------------

    override fun at(position: Float, effect: Effect): PlacedEffect {
        return after(position, effect)
    }

    override fun between(start: Float, end: Float, effect: Effect): PlacedEffect {
        return addEffect(effect).also {
            absoluteEffectPlacements[it.id] = EffectPlacement.between(start, end).value
        }
    }

    override fun before(position: Float, effect: Effect): PlacedEffect {
        return addEffect(effect).also {
            absoluteEffectPlacements[it.id] = EffectPlacement.before(position).value
        }
    }

    override fun before(otherEffect: PlacedEffect, effect: Effect): PlacedEffect {
        require(placedEffects.containsKey(otherEffect.id))
        require(!relativeEffectPlacements.containsKey(-otherEffect.id))
        return addEffect(effect).also { relativeEffectPlacements[-otherEffect.id] = it.id }
    }

    override fun after(position: Float, effect: Effect): PlacedEffect {
        return addEffect(effect).also {
            absoluteEffectPlacements[it.id] = EffectPlacement.after(position).value
        }
    }

    override fun after(otherEffect: PlacedEffect, effect: Effect): PlacedEffect {
        require(placedEffects.containsKey(otherEffect.id))
        require(!relativeEffectPlacements.containsKey(otherEffect.id))
        return addEffect(effect).also { relativeEffectPlacements[otherEffect.id] = it.id }
    }

    private fun addEffect(effect: Effect): PlacedEffect {
        return PlacedEffect(placedEffects.size + 1).also { placedEffects[it.id] = effect }
    }

    // ----- EffectApplyScope implementation -------------------------------------------------------

    override fun addSegmentHandler(key: SegmentKey, handler: OnChangeSegmentHandler) {
        require(!segmentHandlers.containsKey(key))
        segmentHandlers[key] = handler
    }

    override fun baseValue(position: Float): Float {
        return baseMapping.map(position)
    }

    override var minLimit: Float = Float.NaN
    override lateinit var minLimitKey: BreakpointKey
    override var minLimitGuarantee: Guarantee = Guarantee.None
    override var minLimitSpring: SpringParameters = SpringParameters.Snap

    override var maxLimit: Float = Float.NaN
    override lateinit var maxLimitKey: BreakpointKey
    override var maxLimitGuarantee: Guarantee = Guarantee.None
    override var maxLimitSpring: SpringParameters = SpringParameters.Snap
    override var maxLimitSemantics: List<SemanticValue<*>> = emptyList()

    private var forwardInvoked = false
    private var backwardInvoked = false

    override fun unidirectional(
        initialMapping: Mapping,
        semantics: List<SemanticValue<*>>,
        init: DirectionalBuilderScope.() -> Unit,
    ) {
        forward(initialMapping, semantics, init)
        backward(initialMapping, semantics, init)
    }

    override fun unidirectional(mapping: Mapping, semantics: List<SemanticValue<*>>) {
        forward(mapping, semantics)
        backward(mapping, semantics)
    }

    override fun forward(
        initialMapping: Mapping,
        semantics: List<SemanticValue<*>>,
        init: DirectionalBuilderScope.() -> Unit,
    ) {
        check(!forwardInvoked) { "Cannot define forward spec more than once" }
        forwardInvoked = true

        forwardBuilder.prepareBuilderFn(initialMapping, semantics)
        forwardBuilder.init()
    }

    override fun forward(mapping: Mapping, semantics: List<SemanticValue<*>>) {
        check(!forwardInvoked) { "Cannot define forward spec more than once" }
        forwardInvoked = true

        forwardBuilder.prepareBuilderFn(mapping, semantics)
    }

    override fun backward(
        initialMapping: Mapping,
        semantics: List<SemanticValue<*>>,
        init: DirectionalBuilderScope.() -> Unit,
    ) {
        check(!backwardInvoked) { "Cannot define backward spec more than once" }
        backwardInvoked = true

        reverseBuilder.prepareBuilderFn(initialMapping, semantics)
        reverseBuilder.init()
    }

    override fun backward(mapping: Mapping, semantics: List<SemanticValue<*>>) {
        check(!backwardInvoked) { "Cannot define backward spec more than once" }
        backwardInvoked = true

        reverseBuilder.prepareBuilderFn(mapping, semantics)
    }

    private fun applyEffect(effect: Effect) {
        if (effect == EmptyPlaceholder) {
            val maxBreakpoint = Breakpoint(maxLimitKey, maxLimit, maxLimitSpring, maxLimitGuarantee)
            builders.forEach { builder ->
                builder.mappings += baseMapping
                builder.breakpoints += maxBreakpoint
            }
            return
        }

        val initialForwardSize = forwardBuilder.breakpoints.size
        val initialReverseSize = reverseBuilder.breakpoints.size

        forwardInvoked = false
        backwardInvoked = false

        with(effect) { createSpec() }

        check(forwardInvoked) { "forward() spec not defined during createSpec()" }
        check(backwardInvoked) { "backward() spec not defined during createSpec()" }

        builders.forEachIndexed { index, builder ->
            val initialSize = if (index == 0) initialForwardSize else initialReverseSize

            require(minLimitKey != maxLimitKey)
            require(minLimit < maxLimit)

            require(builder.breakpoints[initialSize - 1].key == minLimitKey)

            builder.finalizeBuilderFn(
                maxLimit,
                maxLimitKey,
                maxLimitSpring,
                maxLimitGuarantee,
                maxLimitSemantics,
            )
            check(builder.breakpoints.size > initialSize)

            // Check whether minLimit spring or guarantee have been updated.
            val oldMinBreakpoint = builder.breakpoints[initialSize - 1]
            if (
                oldMinBreakpoint.spring != minLimitSpring ||
                    oldMinBreakpoint.guarantee != minLimitGuarantee
            ) {
                builder.breakpoints[initialSize - 1] =
                    Breakpoint(minLimitKey, minLimit, minLimitSpring, minLimitGuarantee)
            }
        }
    }

    companion object {
        private val EmptyPlaceholder =
            object : Effect {
                override fun EffectApplyScope.createSpec() {
                    throw UnsupportedOperationException()
                }
            }
    }
}
