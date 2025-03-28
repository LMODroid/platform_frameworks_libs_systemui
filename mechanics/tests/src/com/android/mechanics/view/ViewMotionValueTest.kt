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

package com.android.mechanics.view

import android.animation.AnimatorRuleRecordingSpec
import android.animation.AnimatorTestRuleToolkit
import android.animation.MotionControl
import android.animation.MotionControlScope
import android.animation.recordMotion
import android.platform.test.annotations.MotionTest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.MotionValueTest.Companion.B1
import com.android.mechanics.MotionValueTest.Companion.B2
import com.android.mechanics.MotionValueTest.Companion.specBuilder
import com.android.mechanics.spec.Breakpoint
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.SegmentKey
import com.android.mechanics.spec.SemanticKey
import com.android.mechanics.spec.with
import com.android.mechanics.testing.EmptyTestActivity
import com.android.mechanics.testing.VerifyTimeSeriesResult
import com.android.mechanics.testing.VerifyTimeSeriesResult.AssertTimeSeriesMatchesGolden
import com.android.mechanics.testing.asDataPoint
import com.android.mechanics.testing.input
import com.android.mechanics.testing.isStable
import com.android.mechanics.testing.output
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.golden.FeatureCapture
import platform.test.motion.golden.TimeSeries
import platform.test.motion.golden.asDataPoint
import platform.test.motion.testing.createGoldenPathManager
import platform.test.screenshot.PathConfig
import platform.test.screenshot.PathElementNoContext

/**
 * NOTE: This only tests the lifecycle of ViewMotionValue, plus some basic animations.
 *
 * Most code is shared with MotionValue, and tested there.
 */
@RunWith(AndroidJUnit4::class)
@MotionTest
class ViewMotionValueTest {
    private val goldenPathManager =
        createGoldenPathManager(
            "frameworks/libs/systemui/mechanics/tests/goldens",
            // The ViewMotionValue goldens do not currently match MotionValue goldens, because
            // the ViewMotionValue computes the output at the beginning of the new frame, while
            // MotionValue computes it at when read. Therefore, the output of these goldens is
            // delayed by one frame.
            PathConfig(PathElementNoContext("base", isDir = true, { "view" })),
        )

    private val testScope = TestScope()

    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(EmptyTestActivity::class.java)
    @get:Rule(order = 2) val animatorTestRule = android.animation.AnimatorTestRule(this)

    @get:Rule(order = 3)
    val motionRule =
        MotionTestRule(
            AnimatorTestRuleToolkit(animatorTestRule, testScope) { activityRule.scenario },
            goldenPathManager,
        )

    @Test
    fun emptySpec_outputMatchesInput_withoutAnimation() =
        goldenTest(
            spec = MotionSpec.Empty,
            verifyTimeSeries = {
                // Output always matches the input
                assertThat(output).containsExactlyElementsIn(input).inOrder()
                // There must never be an ongoing animation.
                assertThat(isStable).doesNotContain(false)

                AssertTimeSeriesMatchesGolden
            },
        ) {
            animateValueTo(100f)
        }

    @Test
    fun segmentChange_animatedWhenReachingBreakpoint() =
        goldenTest(spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One)) {
            animateValueTo(1f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun semantics_returnsValueMatchingSegment() {
        activityRule.scenario.onActivity {
            val s1 = SemanticKey<String>("Foo")
            val spec =
                specBuilder(Mapping.Zero, semantics = listOf(s1 with "zero")) {
                    constantValue(1f, 1f, semantics = listOf(s1 with "one"))
                    constantValue(2f, 2f, semantics = listOf(s1 with "two"))
                }

            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val underTest = ViewMotionValue(0f, gestureContext, spec)

            assertThat(underTest[s1]).isEqualTo("zero")
            underTest.input = 2f
            animatorTestRule.advanceTimeBy(16L)
            assertThat(underTest[s1]).isEqualTo("two")
        }
    }

    @Test
    fun segment_returnsCurrentSegmentKey() {
        activityRule.scenario.onActivity {
            val spec =
                specBuilder(Mapping.Zero) {
                    constantValue(1f, 1f, key = B1)
                    constantValue(2f, 2f, key = B2)
                }

            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val underTest = ViewMotionValue(1f, gestureContext, spec)

            assertThat(underTest.segmentKey).isEqualTo(SegmentKey(B1, B2, InputDirection.Max))
            underTest.input = 2f
            animatorTestRule.advanceTimeBy(16L)
            assertThat(underTest.segmentKey)
                .isEqualTo(SegmentKey(B2, Breakpoint.maxLimit.key, InputDirection.Max))
        }
    }

    @Test
    fun gestureContext_listensToGestureContextUpdates() =
        goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .completeWith(Mapping.One, guarantee = Guarantee.GestureDragDelta(3f))
        ) {
            animateValueTo(1f, changePerFrame = 0.5f)
            while (!underTest.isStable) {
                gestureContext.dragOffset += 0.5f
                awaitFrames()
            }
        }

    @Test
    fun specChange_triggersAnimation() {
        fun generateSpec(offset: Float) =
            specBuilder(Mapping.Zero)
                .toBreakpoint(offset, B1)
                .jumpTo(1f)
                .continueWithTargetValue(2f)
                .toBreakpoint(offset + 1f, B2)
                .completeWith(Mapping.Zero)

        goldenTest(spec = generateSpec(0f), initialValue = .5f) {
            underTest.spec = generateSpec(1f)
            awaitFrames()
            awaitStable()
        }
    }

    @Test
    fun update_triggersCallback() {
        activityRule.scenario.onActivity {
            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val underTest = ViewMotionValue(0f, gestureContext, MotionSpec.Empty)

            var invocationCount = 0
            underTest.addUpdateCallback { invocationCount++ }
            underTest.input = 1f
            repeat(60) { animatorTestRule.advanceTimeBy(16L) }

            assertThat(invocationCount).isEqualTo(2)
        }
    }

    @Test
    fun update_setSameValue_doesNotTriggerCallback() {
        activityRule.scenario.onActivity {
            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val underTest = ViewMotionValue(0f, gestureContext, MotionSpec.Empty)

            var invocationCount = 0
            underTest.addUpdateCallback { invocationCount++ }
            underTest.input = 0f
            repeat(60) { animatorTestRule.advanceTimeBy(16L) }

            assertThat(invocationCount).isEqualTo(0)
        }
    }

    @Test
    fun update_triggersCallbacksWhileAnimating() {
        activityRule.scenario.onActivity {
            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One)
            val underTest = ViewMotionValue(0f, gestureContext, spec)

            var invocationCount = 0
            underTest.addUpdateCallback { invocationCount++ }
            underTest.input = 1f
            repeat(60) { animatorTestRule.advanceTimeBy(16L) }

            assertThat(invocationCount).isEqualTo(16)
        }
    }

    @Test
    fun removeCallback_doesNotTriggerAfterRemoving() {
        activityRule.scenario.onActivity {
            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One)
            val underTest = ViewMotionValue(0f, gestureContext, spec)

            var invocationCount = 0
            val callback = ViewMotionValueListener { invocationCount++ }
            underTest.addUpdateCallback(callback)
            underTest.input = 0.5f
            animatorTestRule.advanceTimeBy(16L)
            assertThat(invocationCount).isEqualTo(2)

            underTest.removeUpdateCallback(callback)
            underTest.input = 1f
            repeat(60) { animatorTestRule.advanceTimeBy(16L) }

            assertThat(invocationCount).isEqualTo(2)
        }
    }

    @Test
    fun debugInspector_sameInstance_whileInUse() {
        activityRule.scenario.onActivity {
            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val underTest = ViewMotionValue(0f, gestureContext, MotionSpec.Empty)

            val originalInspector = underTest.debugInspector()
            assertThat(underTest.debugInspector()).isSameInstanceAs(originalInspector)
        }
    }

    @Test
    fun debugInspector_newInstance_afterUnused() {
        activityRule.scenario.onActivity {
            val gestureContext = DistanceGestureContext(0f, InputDirection.Max, 5f)
            val underTest = ViewMotionValue(0f, gestureContext, MotionSpec.Empty)

            val originalInspector = underTest.debugInspector()
            originalInspector.dispose()
            assertThat(underTest.debugInspector()).isNotSameInstanceAs(originalInspector)
        }
    }

    // goldenTest and InputScope mimics what is done in MotionValueToolkit. Inlining here, as
    // it's not intended to be used more widely than a couple basic tests here.
    private fun goldenTest(
        spec: MotionSpec,
        initialValue: Float = 0f,
        initialDirection: InputDirection = InputDirection.Max,
        directionChangeSlop: Float = 5f,
        stableThreshold: Float = 0.01f,
        verifyTimeSeries: TimeSeries.() -> VerifyTimeSeriesResult = {
            VerifyTimeSeriesResult.AssertTimeSeriesMatchesGolden
        },
        testInput: suspend InputScope.() -> Unit,
    ) = runTest {
        lateinit var motionValue: ViewMotionValue
        val gestureContext =
            DistanceGestureContext(initialValue, initialDirection, directionChangeSlop)

        activityRule.scenario.onActivity {
            motionValue =
                ViewMotionValue(
                    initialValue,
                    gestureContext,
                    spec,
                    stableThreshold = stableThreshold,
                )
        }

        val motionControl = MotionControl {
            testInput.invoke(InputScope(this, motionValue, gestureContext))
            // AnimatorTestRule always records the frame first, then calls nextFrame() internally.
            // Since the ViewMotionValue is updated during the nextFrame() only, always record one
            // extra frame to capture the last computed state.
            awaitFrames()
        }

        val inspector = motionValue.debugInspector()

        val recordedMotion =
            motionRule.recordMotion(
                AnimatorRuleRecordingSpec(inspector, motionControl) {
                    on({ it.frame }) {
                        feature(FeatureCapture("input") { it.input.asDataPoint() })
                        feature(
                            FeatureCapture("gestureDirection") {
                                it.gestureDirection.name.asDataPoint()
                            }
                        )
                        feature(FeatureCapture("output") { it.output.asDataPoint() })
                        feature(FeatureCapture("outputTarget") { it.outputTarget.asDataPoint() })
                        feature(
                            FeatureCapture("outputSpring") { it.springParameters.asDataPoint() }
                        )
                        feature(FeatureCapture("isStable") { it.isStable.asDataPoint() })
                    }
                }
            )

        val skipGoldenVerification = verifyTimeSeries.invoke(recordedMotion.timeSeries)
        if (skipGoldenVerification == VerifyTimeSeriesResult.AssertTimeSeriesMatchesGolden) {
            motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden()
        }
        activityRule.scenario.onActivity { motionValue.dispose() }
    }

    private class InputScope(
        motionControlScope: MotionControlScope,
        val underTest: ViewMotionValue,
        val gestureContext: DistanceGestureContext,
    ) : MotionControlScope by motionControlScope {
        val input: Float
            get() = underTest.input

        suspend fun awaitStable() {
            awaitCondition { underTest.isStable }
        }

        fun updateValue(position: Float) {
            gestureContext.dragOffset = position
            underTest.input = position
        }

        suspend fun animateValueTo(
            targetValue: Float,
            changePerFrame: Float = abs(input - targetValue) / 5f,
        ) {
            require(changePerFrame > 0f)
            var currentValue = input
            val delta = targetValue - currentValue
            val step = changePerFrame * delta.sign

            val stepCount = floor((abs(delta) / changePerFrame) - 1).toInt()
            repeat(stepCount) {
                currentValue += step
                updateValue(currentValue)
                awaitFrames()
            }

            updateValue(targetValue)
            awaitFrames()
        }

        suspend fun animatedInputSequence(vararg values: Float) {
            values.forEach {
                updateValue(it)
                awaitFrames()
            }
        }

        fun reset(position: Float, direction: InputDirection) {
            underTest.input = position
            gestureContext.reset(position, direction)
        }
    }
}
