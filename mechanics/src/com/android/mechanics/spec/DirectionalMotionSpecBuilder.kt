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

package com.android.mechanics.spec

import com.android.mechanics.spring.SpringParameters

/**
 * Builds a [DirectionalMotionSpec] by defining a sequence of ([Breakpoint], [Mapping]) pairs.
 *
 * This function simplifies the creation of complex motion specifications. It allows you to define a
 * series of motion segments, each with its own behavior, separated by breakpoints. The breakpoints
 * and their corresponding segments will always be ordered from min to max value, regardless of how
 * the `DirectionalMotionSpec` is applied.
 *
 * Example Usage:
 * ```kotlin
 * val motionSpec = buildDirectionalMotionSpec(
 *     defaultSpring = materialSpatial,
 *
 *     // Start as a constant transition, always 0.
 *     initialMapping = Mapping.Zero
 * ) {
 *     // At breakpoint 10: Linear transition from 0 to 50.
 *     target(breakpoint = 10f, from = 0f, to = 50f)
 *
 *     // At breakpoint 20: Jump +5, and constant value 55.
 *     constantValueFromCurrent(breakpoint = 20f, delta = 5f)
 *
 *     // At breakpoint 30: Jump to 40. Linear mapping using: progress_since_breakpoint * fraction.
 *     fractionalInput(breakpoint = 30f, from = 40f, fraction = 2f)
 * }
 * ```
 *
 * @param defaultSpring The default [SpringParameters] to use for all breakpoints.
 * @param initialMapping The initial [Mapping] for the first segment (defaults to
 *   [Mapping.Identity]).
 * @param init A lambda function that configures the [DirectionalMotionSpecBuilder]. The lambda
 *   should return a [CanBeLastSegment] to indicate the end of the spec.
 * @param semantics Semantics specified in this spec, including the initial value applied for
 *   [initialMapping].
 *     @return The constructed [DirectionalMotionSpec].
 */
fun buildDirectionalMotionSpec(
    defaultSpring: SpringParameters,
    initialMapping: Mapping = Mapping.Identity,
    semantics: List<SemanticValue<*>> = emptyList(),
    init: DirectionalMotionSpecBuilder.() -> CanBeLastSegment,
): DirectionalMotionSpec {
    return DirectionalMotionSpecBuilderImpl(defaultSpring)
        .also { it.mappings += initialMapping }
        .also { it.semantics += semantics.map { SegmentSemanticValuesBuilder(it) } }
        .also { it.init() }
        .build()
}

/**
 * Builds a simple [DirectionalMotionSpec] with a single segment.
 *
 * @param mapping The [Mapping] to apply to the segment. Defaults to [Mapping.Identity].
 * @param semantics Semantics values for this spec.
 * @return A new [DirectionalMotionSpec] instance configured with the provided parameters.
 */
fun buildDirectionalMotionSpec(
    mapping: Mapping = Mapping.Identity,
    semantics: List<SemanticValue<*>> = emptyList(),
): DirectionalMotionSpec {
    fun <T> toSegmentSemanticValues(semanticValue: SemanticValue<T>) =
        SegmentSemanticValues(semanticValue.key, listOf(semanticValue.value))

    return DirectionalMotionSpec(
        listOf(Breakpoint.minLimit, Breakpoint.maxLimit),
        listOf(mapping),
        semantics.map { toSegmentSemanticValues(it) },
    )
}

/**
 * Defines the contract for building a [DirectionalMotionSpec].
 *
 * Provides methods to define breakpoints and mappings for the motion specification.
 */
interface DirectionalMotionSpecBuilder {
    /** The default [SpringParameters] used for breakpoints. */
    val defaultSpring: SpringParameters

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment to
     * linearly interpolate from a starting value ([from]) to the desired target value ([to]).
     *
     * Note: This segment cannot be used as the last segment in the specification, as it requires a
     * subsequent breakpoint to define the target value.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param from The output value at the previous breakpoint, explicitly setting the starting
     *   point for the linear mapping.
     * @param to The desired output value at the new breakpoint.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     */
    fun target(
        breakpoint: Float,
        from: Float,
        to: Float,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
    )

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment to
     * linearly interpolate from the current output value (optionally with an offset of [delta]) to
     * the desired target value ([to]).
     *
     * Note: This segment cannot be used as the last segment in the specification, as it requires a
     * subsequent breakpoint to define the target value.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param to The desired output value at the new breakpoint.
     * @param delta An optional offset to apply to the calculated starting value. Defaults to 0f.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     */
    fun targetFromCurrent(
        breakpoint: Float,
        to: Float,
        delta: Float = 0f,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
    )

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment to
     * linearly interpolate from a starting value ([from]) and then continue with a fractional input
     * ([fraction]).
     *
     * Note: This segment can be used as the last segment in the specification.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param from The output value at the previous breakpoint, explicitly setting the starting
     *   point for the linear mapping.
     * @param fraction The fractional multiplier applied to the input difference between
     *   breakpoints.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     */
    fun fractionalInput(
        breakpoint: Float,
        from: Float,
        fraction: Float,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
    ): CanBeLastSegment

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment to
     * linearly interpolate from the current output value (optionally with an offset of [delta]) and
     * then continue with a fractional input ([fraction]).
     *
     * Note: This segment can be used as the last segment in the specification.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param fraction The fractional multiplier applied to the input difference between
     *   breakpoints.
     * @param delta An optional offset to apply to the calculated starting value. Defaults to 0f.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     */
    fun fractionalInputFromCurrent(
        breakpoint: Float,
        fraction: Float,
        delta: Float = 0f,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
    ): CanBeLastSegment

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment to output
     * a constant value ([value]).
     *
     * Note: This segment can be used as the last segment in the specification.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param value The constant output value for this segment.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     */
    fun constantValue(
        breakpoint: Float,
        value: Float,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
    ): CanBeLastSegment

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment to output
     * a constant value derived from the current output value (optionally with an offset of
     * [delta]).
     *
     * Note: This segment can be used as the last segment in the specification.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param delta An optional offset to apply to the mapped value to determine the constant value.
     *   Defaults to 0f.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     */
    fun constantValueFromCurrent(
        breakpoint: Float,
        delta: Float = 0f,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
    ): CanBeLastSegment

    /**
     * Ends the current segment at the [breakpoint] position and defines the next segment using the
     * provided [mapping].
     *
     * Note: This segment can be used as the last segment in the specification.
     *
     * @param breakpoint The breakpoint defining the end of the current segment and the start of the
     *   next.
     * @param spring The [SpringParameters] for the transition to this breakpoint. Defaults to
     *   [defaultSpring].
     * @param guarantee The animation guarantee for this transition. Defaults to [Guarantee.None].
     * @param key A unique [BreakpointKey] for this breakpoint. Defaults to a newly generated key.
     * @param semantics Updated semantics values to be applied. Must be a subset of the
     *   [SemanticKey]s used when first creating this builder.
     * @param mapping The custom [Mapping] to use.
     */
    fun mapping(
        breakpoint: Float,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
        key: BreakpointKey = BreakpointKey(),
        semantics: List<SemanticValue<*>> = emptyList(),
        mapping: Mapping,
    ): CanBeLastSegment
}

/** Marker interface to indicate that a segment can be the last one in a [DirectionalMotionSpec]. */
sealed interface CanBeLastSegment

private data object CanBeLastSegmentImpl : CanBeLastSegment

private class SegmentSemanticValuesBuilder<T>(seed: SemanticValue<T>) {
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

private class DirectionalMotionSpecBuilderImpl(override val defaultSpring: SpringParameters) :
    DirectionalMotionSpecBuilder {
    private val breakpoints = mutableListOf(Breakpoint.minLimit)
    val semantics = mutableListOf<SegmentSemanticValuesBuilder<*>>()
    val mappings = mutableListOf<Mapping>()
    private var sourceValue: Float = Float.NaN
    private var targetValue: Float = Float.NaN
    private var fractionalMapping: Float = Float.NaN
    private var breakpointPosition: Float = Float.NaN
    private var breakpointKey: BreakpointKey? = null

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
