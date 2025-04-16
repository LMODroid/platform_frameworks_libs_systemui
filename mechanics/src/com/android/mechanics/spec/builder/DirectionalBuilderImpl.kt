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

import com.android.mechanics.spec.Breakpoint
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.DirectionalMotionSpec
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.SegmentSemanticValues
import com.android.mechanics.spec.SemanticValue
import com.android.mechanics.spring.SpringParameters

/**
 * Internal, reusable implementation of the [DirectionalBuilderScope].
 *
 * Clients must use [buildDirectionalMotionSpec] instead.
 */
internal class DirectionalBuilderImpl(override val defaultSpring: SpringParameters) :
    DirectionalBuilderScope {
    internal val breakpoints = mutableListOf(Breakpoint.minLimit)
    internal val semantics = mutableListOf<SegmentSemanticValuesBuilder<*>>()
    internal val mappings = mutableListOf<Mapping>()
    private var sourceValue: Float = Float.NaN
    private var targetValue: Float = Float.NaN
    private var fractionalMapping: Float = Float.NaN
    private var breakpointPosition: Float = Float.NaN
    private var breakpointKey: BreakpointKey? = null

    /* Creates the [DirectionalMotionSpec] from the current builder state. */
    fun build(): DirectionalMotionSpec {
        completeImpl()
        val semantics =
            semantics.map { builder ->
                with(builder) {
                    backfill(mappings.size)
                    build()
                }
            }

        return DirectionalMotionSpec(breakpoints.toList(), mappings.toList(), semantics)
    }

    override fun target(
        breakpoint: Float,
        from: Float,
        to: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
    ) {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        jumpToImpl(from, spring, guarantee)
        continueWithTargetValueImpl(to)
    }

    override fun targetFromCurrent(
        breakpoint: Float,
        to: Float,
        delta: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
    ) {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        jumpByImpl(delta, spring, guarantee)
        continueWithTargetValueImpl(to)
    }

    override fun fractionalInput(
        breakpoint: Float,
        from: Float,
        fraction: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
    ): CanBeLastSegment {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        jumpToImpl(from, spring, guarantee)
        continueWithFractionalInputImpl(fraction)
        return CanBeLastSegmentImpl
    }

    override fun fractionalInputFromCurrent(
        breakpoint: Float,
        fraction: Float,
        delta: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
    ): CanBeLastSegment {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        jumpByImpl(delta, spring, guarantee)
        continueWithFractionalInputImpl(fraction)
        return CanBeLastSegmentImpl
    }

    override fun constantValue(
        breakpoint: Float,
        value: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
    ): CanBeLastSegment {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        jumpToImpl(value, spring, guarantee)
        continueWithConstantValueImpl()
        return CanBeLastSegmentImpl
    }

    override fun constantValueFromCurrent(
        breakpoint: Float,
        delta: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
    ): CanBeLastSegment {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        jumpByImpl(delta, spring, guarantee)
        continueWithConstantValueImpl()
        return CanBeLastSegmentImpl
    }

    override fun mapping(
        breakpoint: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
        key: BreakpointKey,
        semantics: List<SemanticValue<*>>,
        mapping: Mapping,
    ): CanBeLastSegment {
        applySemantics(semantics)
        toBreakpointImpl(breakpoint, key)
        continueWithImpl(mapping, spring, guarantee)
        return CanBeLastSegmentImpl
    }

    private fun applySemantics(toApply: List<SemanticValue<*>>) {
        toApply.forEach { (key, value) ->
            val semanticValuesBuilder =
                checkNotNull(semantics.first { it.key == key }) {
                    "semantic key $key not initially registered"
                }

            semanticValuesBuilder.backfill(mappings.size)
            semanticValuesBuilder.append(value)
        }
    }

    private fun continueWithTargetValueImpl(target: Float) {
        check(sourceValue.isFinite())

        targetValue = target
    }

    private fun continueWithFractionalInputImpl(fraction: Float) {
        check(sourceValue.isFinite())

        fractionalMapping = fraction
    }

    private fun continueWithConstantValueImpl() {
        check(sourceValue.isFinite())

        mappings.add(Mapping.Fixed(sourceValue))
        sourceValue = Float.NaN
    }

    private fun jumpToImpl(value: Float, spring: SpringParameters, guarantee: Guarantee) {
        check(sourceValue.isNaN())

        doAddBreakpointImpl(spring, guarantee)
        sourceValue = value
    }

    private fun jumpByImpl(delta: Float, spring: SpringParameters, guarantee: Guarantee) {
        check(sourceValue.isNaN())

        val breakpoint = doAddBreakpointImpl(spring, guarantee)
        sourceValue = mappings.last().map(breakpoint.position) + delta
    }

    private fun continueWithImpl(mapping: Mapping, spring: SpringParameters, guarantee: Guarantee) {
        check(sourceValue.isNaN())

        doAddBreakpointImpl(spring, guarantee)
        mappings.add(mapping)
    }

    private fun toBreakpointImpl(atPosition: Float, key: BreakpointKey) {
        check(breakpointPosition.isNaN())
        check(breakpointKey == null)

        if (!targetValue.isNaN() || !fractionalMapping.isNaN()) {
            check(!sourceValue.isNaN())

            val sourcePosition = breakpoints.last().position
            val breakpointDistance = atPosition - sourcePosition
            val mapping =
                if (breakpointDistance == 0f) {
                    Mapping.Fixed(sourceValue)
                } else {

                    if (fractionalMapping.isNaN()) {
                        val delta = targetValue - sourceValue
                        fractionalMapping = delta / (atPosition - sourcePosition)
                    } else {
                        val delta = (atPosition - sourcePosition) * fractionalMapping
                        targetValue = sourceValue + delta
                    }

                    val offset = sourceValue - (sourcePosition * fractionalMapping)
                    Mapping.Linear(fractionalMapping, offset)
                }

            mappings.add(mapping)
            targetValue = Float.NaN
            sourceValue = Float.NaN
            fractionalMapping = Float.NaN
        }

        breakpointPosition = atPosition
        breakpointKey = key
    }

    private fun completeImpl() {
        check(targetValue.isNaN()) { "cant specify target value for last segment" }

        if (!fractionalMapping.isNaN()) {
            check(!sourceValue.isNaN())

            val sourcePosition = breakpoints.last().position

            mappings.add(
                Mapping.Linear(
                    fractionalMapping,
                    sourceValue - (sourcePosition * fractionalMapping),
                )
            )
        }

        breakpoints.add(Breakpoint.maxLimit)
    }

    private fun doAddBreakpointImpl(
        springSpec: SpringParameters,
        guarantee: Guarantee,
    ): Breakpoint {
        check(breakpointPosition.isFinite())
        return Breakpoint(checkNotNull(breakpointKey), breakpointPosition, springSpec, guarantee)
            .also {
                breakpoints.add(it)
                breakpointPosition = Float.NaN
                breakpointKey = null
            }
    }
}

internal class SegmentSemanticValuesBuilder<T>(seed: SemanticValue<T>) {
    val key = seed.key
    private val values = mutableListOf(seed.value)

    fun backfill(segmentCount: Int) {
        val lastValue = values.last()
        repeat(segmentCount - values.size) { values.add(lastValue) }
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> append(value: V) {
        values.add(value as T)
    }

    fun build() = SegmentSemanticValues(key, values.toList())
}

private data object CanBeLastSegmentImpl : CanBeLastSegment
