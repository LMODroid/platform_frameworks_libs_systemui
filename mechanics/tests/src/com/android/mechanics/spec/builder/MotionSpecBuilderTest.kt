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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.effects.FixedValue
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.SemanticKey
import com.android.mechanics.spec.SemanticValue
import com.android.mechanics.spring.SpringParameters
import com.android.mechanics.testing.FakeMotionSpecBuilderContext
import com.android.mechanics.testing.MotionSpecSubject.Companion.assertThat
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionSpecBuilderTest : MotionBuilderContext by FakeMotionSpecBuilderContext.Default {

    // placement & ordering
    // placement types
    // placement issues
    // before & after mapping, springs etc

    @Test
    fun motionSpec_empty_usesBaseMapping() {
        val result = spatialMotionSpec {}

        assertThat(result).bothDirections().mappingsMatch(Mapping.Identity)
        assertThat(result).bothDirections().breakpoints().isEmpty()
    }

    @Test
    fun placement_absoluteAfter_createsTwoSegments() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                after(42f, FixedValue(1f))
            }

        assertThat(result).bothDirections().mappingsMatch(Mapping.Zero, Mapping.One)
        assertThat(result).bothDirections().breakpointsPositionsMatch(42f)
    }

    @Test
    fun placement_absoluteBefore_createsTwoSegments() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                before(42f, FixedValue(1f))
            }

        assertThat(result).bothDirections().mappingsMatch(Mapping.One, Mapping.Zero)
        assertThat(result).bothDirections().breakpointsPositionsMatch(42f)
    }

    @Test
    fun placement_absoluteBetween_createsThreeSegments() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(42f, 43f, FixedValue(1f))
            }

        assertThat(result).bothDirections().mappingsMatch(Mapping.Zero, Mapping.One, Mapping.Zero)
        assertThat(result).bothDirections().breakpointsPositionsMatch(42f, 43f)
    }

    @Test
    fun placement_absoluteBetweenReverse_createsThreeSegments() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(43f, 42f, FixedValue(1f))
            }

        assertThat(result).bothDirections().mappingsMatch(Mapping.Zero, Mapping.One, Mapping.Zero)
        assertThat(result).bothDirections().breakpointsPositionsMatch(42f, 43f)
    }

    @Test
    fun placement_adjacent_sharesBreakpoint() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, FixedValue(1f))
                between(2f, 3f, FixedValue(2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 2f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 2f, 3f)
    }

    @Test
    fun placement_multiple_baseMappingInBetween() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, FixedValue(1f))
                // Implicit baseMapping between 2 & 3
                between(3f, 4f, FixedValue(2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 0f, 2f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 2f, 3f, 4f)
    }

    @Test
    fun placement_overlapping_throws() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                    between(1f, 2f, FixedValue(1f))
                    between(1.5f, 2.5f, FixedValue(2f))
                }
            }
        assertThat(exception).hasMessageThat().contains("overlap")
    }

    @Test
    fun placement_embedded_throws() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                    between(1f, 3f, FixedValue(1f))
                    between(1.5f, 2.5f, FixedValue(2f))
                }
            }
        assertThat(exception).hasMessageThat().contains("overlap")
    }

    @Test
    fun placement_subsequent_extendsToNext() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                after(1f, FixedValue(1f))
                between(3f, 4f, FixedValue(2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 2f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 3f, 4f)
    }

    @Test
    fun placement_subsequent_extendsToPrevious() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, FixedValue(1f))
                before(4f, FixedValue(2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 2f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 2f, 4f)
    }

    @Test
    fun placement_subsequent_bothExtend_throws() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                    after(1f, FixedValue(1f))
                    before(3f, FixedValue(2f))
                }
            }
        assertThat(exception).hasMessageThat().contains("extend")
    }

    @Test
    fun placement_withFixedExtent_after_limitsEffect() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                after(1f, FixedValueWithExtent(1f, 2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 3f)
    }

    @Test
    fun placement_withFixedExtent_before_limitsEffect() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                before(1f, FixedValueWithExtent(1f, 2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(-1f, 1f)
    }

    @Test
    fun placement_relative_afterEffect() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                val effect1 = between(1f, 2f, FixedValue(1f))
                after(effect1, FixedValue(2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 2f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 2f)
    }

    @Test
    fun placement_relative_beforeEffect() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                val effect1 = between(1f, 2f, FixedValue(1f))
                before(effect1, FixedValue(2f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(2f, 1f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 2f)
    }

    @Test
    fun placement_relative_chainOfMappings() {
        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                val rootEffect = after(1f, FixedValueWithExtent(-1f, 2f))

                val left = before(rootEffect, FixedValueWithExtent(-2f, 3f))
                before(left, FixedValueWithExtent(-3f, 4f))

                val right = after(rootEffect, FixedValueWithExtent(-4f, 3f))
                after(right, FixedValueWithExtent(-5f, 4f))
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, -3f, -2f, -1f, -4f, -5f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(-6f, -2f, 1f, 3f, 6f, 10f)
    }

    @Test
    fun placement_relative_overlappingChain_throws() {
        assertFailsWith<IllegalArgumentException> {
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                val rootEffect = between(1f, 3f, FixedValue(-1f))
                val left = before(rootEffect, FixedValue(-2f))
                after(left, FixedValue(-3f))
            }
        }
    }

    @Test
    fun effect_differentReverseSpec() {
        val effect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    forward(Mapping.One)
                    backward(Mapping.Two)
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, effect)
            }

        assertThat(result).maxDirection().fixedMappingsMatch(0f, 1f, 0f)
        assertThat(result).maxDirection().breakpointsPositionsMatch(1f, 2f)

        assertThat(result).minDirection().fixedMappingsMatch(0f, 2f, 0f)
        assertThat(result).minDirection().breakpointsPositionsMatch(1f, 2f)
    }

    @Test
    fun effect_separateReverseSpec_withBuilder_canProduceDifferentSegmentCount() {
        val effect =
            object : Effect.PlaceableBetween {
                override fun EffectApplyScope.createSpec(
                    minLimit: Float,
                    minLimitKey: BreakpointKey,
                    maxLimit: Float,
                    maxLimitKey: BreakpointKey,
                    placement: EffectPlacement,
                ) {
                    forward(Mapping.One) { constantValue(breakpoint = minLimit + 0.5f, 10f) }
                    backward(Mapping.Two)
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, effect)
            }

        assertThat(result).maxDirection().fixedMappingsMatch(0f, 1f, 10f, 0f)
        assertThat(result).maxDirection().breakpointsPositionsMatch(1f, 1.5f, 2f)

        assertThat(result).minDirection().fixedMappingsMatch(0f, 2f, 0f)
        assertThat(result).minDirection().breakpointsPositionsMatch(1f, 2f)
    }

    @Test
    fun effect_identicalBackward_withBuilder_producesSameSpecInBothDirections() {
        val effect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    val key = BreakpointKey()
                    unidirectional(Mapping.One) {
                        constantValue(breakpoint = 1.5f, value = 10f, key = key)
                    }
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, effect)
            }

        assertThat(result).bothDirections().fixedMappingsMatch(0f, 1f, 10f, 0f)
        assertThat(result).bothDirections().breakpointsPositionsMatch(1f, 1.5f, 2f)
    }

    @Test
    fun effect_setBreakpointBeforeMinLimit_throws() {
        val rogueEffect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    unidirectional(Mapping.One) {
                        this.constantValue(breakpoint = 0.5f, value = 0f)
                    }
                }
            }

        assertFailsWith<IllegalStateException> {
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, rogueEffect)
            }
        }
    }

    @Test
    fun effect_setBreakpointAfterMinLimit_throws() {
        val rogueEffect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    unidirectional(Mapping.One) {
                        this.constantValue(breakpoint = 2.5f, value = 0f)
                    }
                }
            }

        assertFailsWith<IllegalStateException> {
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, rogueEffect)
            }
        }
    }

    @Test
    fun effect_modifyMinLimitSpring() {
        val spring = SpringParameters(stiffness = 1f, dampingRatio = 2f)
        val effect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    unidirectional(Mapping.One) { before(spring = spring) }
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, effect)
            }

        assertThat(result).bothDirections().breakpoints().atPosition(1f).spring().isEqualTo(spring)
    }

    @Test
    fun effect_modifyMaxLimitSpring() {
        val spring = SpringParameters(stiffness = 1f, dampingRatio = 2f)
        val effect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    unidirectional(Mapping.One) { after(spring = spring) }
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, effect)
            }

        assertThat(result).bothDirections().breakpoints().atPosition(2f).spring().isEqualTo(spring)
    }

    @Test
    fun effect_semantics_applyToFullInputRange() {
        val semanticKey = SemanticKey<String>("foo")
        val effect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    unidirectional(
                        Mapping.One,
                        semantics = listOf(SemanticValue(semanticKey, "initial")),
                    ) {
                        constantValue(
                            breakpoint = 1.5f,
                            value = 2f,
                            semantics = listOf(SemanticValue(semanticKey, "second")),
                        )
                    }
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                between(1f, 2f, effect)
            }

        assertThat(result)
            .maxDirection()
            .semantics()
            .withKey(semanticKey)
            .containsExactly("initial", "initial", "second", "second")
            .inOrder()
    }

    @Test
    fun effect_modifyMaxLimitSemantics() {
        val semanticKey = SemanticKey<String>("foo")
        val effect =
            object : SimpleEffect() {
                override fun EffectApplyScope.createSpec() {
                    unidirectional(
                        Mapping.One,
                        semantics = listOf(SemanticValue(semanticKey, "initial")),
                    ) {
                        after(semantics = listOf(SemanticValue(semanticKey, "maxLimit")))
                    }
                }
            }

        val result =
            motionSpec(baseMapping = Mapping.Zero, defaultSpring = spatial.default) {
                val effect1 = between(1f, 2f, effect)
                after(effect1, FixedValue(2f))
            }

        assertThat(result)
            .maxDirection()
            .semantics()
            .withKey(semanticKey)
            .containsExactly("initial", "initial", "maxLimit")
            .inOrder()
    }

    private abstract class SimpleEffect : Effect.PlaceableBetween {
        override fun EffectApplyScope.createSpec(
            minLimit: Float,
            minLimitKey: BreakpointKey,
            maxLimit: Float,
            maxLimitKey: BreakpointKey,
            placement: EffectPlacement,
        ) {
            createSpec()
        }

        abstract fun EffectApplyScope.createSpec()
    }

    private class FixedValueWithExtent(val value: Float, val extent: Float) :
        Effect.PlaceableAfter, Effect.PlaceableBefore {
        override fun MotionBuilderContext.intrinsicSize() = extent

        override fun EffectApplyScope.createSpec(
            minLimit: Float,
            minLimitKey: BreakpointKey,
            maxLimit: Float,
            maxLimitKey: BreakpointKey,
            placement: EffectPlacement,
        ) {
            return unidirectional(Mapping.Fixed(value))
        }
    }
}
