/*
 * Copyright (C) 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.mechanics.testing

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.android.mechanics.DistanceGestureContext
import com.android.mechanics.MotionValue
import com.android.mechanics.debug.FrameData
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.SemanticKey
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion.Companion.create
import platform.test.motion.golden.DataPoint
import platform.test.motion.golden.DataPointType
import platform.test.motion.golden.Feature
import platform.test.motion.golden.FrameId
import platform.test.motion.golden.TimeSeries
import platform.test.motion.golden.TimestampFrameId
import platform.test.motion.golden.asDataPoint

/** Toolkit to support [MotionValue] motion tests. */
class MotionValueToolkit(val composeTestRule: ComposeContentTestRule) {
    companion object {
        internal const val TAG = "MotionValueToolkit"
    }
}

interface InputScope {
    val input: Float
    val gestureContext: DistanceGestureContext
    val underTest: MotionValue

    suspend fun awaitStable()

    suspend fun awaitFrames(frames: Int = 1)

    var directionChangeSlop: Float

    fun updateValue(position: Float)

    suspend fun animateValueTo(
        targetValue: Float,
        changePerFrame: Float = abs(input - targetValue) / 5f,
    )

    suspend fun animatedInputSequence(vararg values: Float)

    fun reset(position: Float, direction: InputDirection)
}

enum class VerifyTimeSeriesResult {
    SkipGoldenVerification,
    AssertTimeSeriesMatchesGolden,
}

class CapturedSemantics<T>(
    val key: SemanticKey<T>,
    val dataPointType: DataPointType<T>,
    val name: String = key.debugLabel,
) {
    fun toDataPoint(frameData: FrameData): DataPoint<T> {
        return dataPointType.makeDataPoint(frameData.semantic(key))
    }
}

fun MotionTestRule<MotionValueToolkit>.goldenTest(
    spec: MotionSpec,
    createDerived: (underTest: MotionValue) -> List<MotionValue> = { emptyList() },
    semantics: List<CapturedSemantics<*>> = emptyList(),
    initialValue: Float = 0f,
    initialDirection: InputDirection = InputDirection.Max,
    directionChangeSlop: Float = 5f,
    stableThreshold: Float = 0.01f,
    verifyTimeSeries: TimeSeries.() -> VerifyTimeSeriesResult = {
        VerifyTimeSeriesResult.AssertTimeSeriesMatchesGolden
    },
    testInput: suspend InputScope.() -> Unit,
) = runTest {
    with(toolkit.composeTestRule) {
        val frameEmitter = MutableStateFlow<Long>(0)

        val testHarness =
            MotionValueTestHarness(
                initialValue,
                initialDirection,
                spec,
                stableThreshold,
                directionChangeSlop,
                frameEmitter.asStateFlow(),
                createDerived,
            )
        val underTest = testHarness.underTest
        val derived = testHarness.derived

        val inspectors = buildMap {
            put(underTest, underTest.debugInspector())
            derived.forEach { put(it, it.debugInspector()) }
        }

        setContent {
            LaunchedEffect(Unit) {
                launch { underTest.keepRunning() }
                derived.forEach { launch { it.keepRunning() } }
            }
        }

        val recordingJob = launch { testInput.invoke(testHarness) }

        waitForIdle()
        mainClock.autoAdvance = false

        val frameIds = mutableListOf<FrameId>()
        val frameData = mutableMapOf<MotionValue, MutableList<FrameData>>()

        fun recordFrame(frameId: TimestampFrameId) {
            frameIds.add(frameId)
            inspectors.forEach { (motionValue, inspector) ->
                frameData.computeIfAbsent(motionValue) { mutableListOf() }.add(inspector.frame)
            }
        }

        val startFrameTime = mainClock.currentTime
        recordFrame(TimestampFrameId(mainClock.currentTime - startFrameTime))
        while (!recordingJob.isCompleted) {
            frameEmitter.tryEmit(mainClock.currentTime + 16)
            runCurrent()
            mainClock.advanceTimeByFrame()
            recordFrame(TimestampFrameId(mainClock.currentTime - startFrameTime))
        }

        val timeSeries =
            TimeSeries(
                frameIds.toList(),
                buildList {
                    frameData.forEach { (motionValue, frames) ->
                        val prefix = if (motionValue == underTest) "" else "${motionValue.label}-"

                        add(Feature("${prefix}input", frames.map { it.input.asDataPoint() }))
                        add(
                            Feature(
                                "${prefix}gestureDirection",
                                frames.map { it.gestureDirection.name.asDataPoint() },
                            )
                        )
                        add(Feature("${prefix}output", frames.map { it.output.asDataPoint() }))
                        add(
                            Feature(
                                "${prefix}outputTarget",
                                frames.map { it.outputTarget.asDataPoint() },
                            )
                        )
                        add(
                            Feature(
                                "${prefix}outputSpring",
                                frames.map { it.springParameters.asDataPoint() },
                            )
                        )
                        add(Feature("${prefix}isStable", frames.map { it.isStable.asDataPoint() }))

                        semantics.forEach { add(Feature(it.name, frames.map(it::toDataPoint))) }
                    }
                },
            )

        inspectors.values.forEach { it.dispose() }

        val recordedMotion = create(timeSeries, screenshots = null)
        val skipGoldenVerification = verifyTimeSeries.invoke(recordedMotion.timeSeries)
        if (skipGoldenVerification == VerifyTimeSeriesResult.AssertTimeSeriesMatchesGolden) {
            assertThat(recordedMotion).timeSeriesMatchesGolden()
        }
    }
}

private class MotionValueTestHarness(
    initialInput: Float,
    initialDirection: InputDirection,
    spec: MotionSpec,
    stableThreshold: Float,
    directionChangeSlop: Float,
    val onFrame: StateFlow<Long>,
    createDerived: (underTest: MotionValue) -> List<MotionValue>,
) : InputScope {

    override var input by mutableFloatStateOf(initialInput)
    override val gestureContext: DistanceGestureContext =
        DistanceGestureContext(initialInput, initialDirection, directionChangeSlop)

    override val underTest =
        MotionValue(
            { input },
            gestureContext,
            stableThreshold = stableThreshold,
            initialSpec = spec,
        )

    val derived = createDerived(underTest)

    override fun updateValue(position: Float) {
        input = position
        gestureContext.dragOffset = position
    }

    override var directionChangeSlop: Float
        get() = gestureContext.directionChangeSlop
        set(value) {
            gestureContext.directionChangeSlop = value
        }

    override suspend fun awaitStable() {
        val debugInspectors = buildList {
            add(underTest.debugInspector())
            addAll(derived.map { it.debugInspector() })
        }
        try {

            onFrame
                // Since this is a state-flow, the current frame is counted too.
                .drop(1)
                .takeWhile { debugInspectors.any { !it.frame.isStable } }
                .collect {}
        } finally {
            debugInspectors.forEach { it.dispose() }
        }
    }

    override suspend fun awaitFrames(frames: Int) {
        onFrame
            // Since this is a state-flow, the current frame is counted too.
            .drop(1)
            .take(frames)
            .collect {}
    }

    override suspend fun animateValueTo(targetValue: Float, changePerFrame: Float) {
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

    override suspend fun animatedInputSequence(vararg values: Float) {
        values.forEach {
            updateValue(it)
            awaitFrames()
        }
    }

    override fun reset(position: Float, direction: InputDirection) {
        input = position
        gestureContext.reset(position, direction)
    }
}
