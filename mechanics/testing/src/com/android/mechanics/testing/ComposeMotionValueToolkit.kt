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
import platform.test.motion.golden.FrameId
import platform.test.motion.golden.TimeSeries
import platform.test.motion.golden.TimestampFrameId

/** Toolkit to support [MotionValue] motion tests. */
class ComposeMotionValueToolkit(val composeTestRule: ComposeContentTestRule) :
    MotionValueToolkit<MotionValue, DistanceGestureContext>() {

    override fun goldenTest(
        motionTestRule: MotionTestRule<*>,
        spec: MotionSpec,
        createDerived: (underTest: MotionValue) -> List<MotionValue>,
        semantics: List<CapturedSemantics<*>>,
        initialValue: Float,
        initialDirection: InputDirection,
        directionChangeSlop: Float,
        stableThreshold: Float,
        verifyTimeSeries: TimeSeries.() -> VerifyTimeSeriesResult,
        testInput: suspend InputScope<MotionValue, DistanceGestureContext>.() -> Unit,
    ) = runTest {
        with(composeTestRule) {
            val frameEmitter = MutableStateFlow<Long>(0)

            val testHarness =
                ComposeMotionValueTestHarness(
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
                createTimeSeries(
                    frameIds,
                    frameData.entries
                        .map { (motionValue, frameData) ->
                            val prefix =
                                if (motionValue == underTest) "" else "${motionValue.label}-"
                            prefix to frameData
                        }
                        .sortedBy { it.first },
                    semantics,
                )

            inspectors.values.forEach { it.dispose() }
            verifyTimeSeries(motionTestRule, timeSeries, verifyTimeSeries)
        }
    }
}

private class ComposeMotionValueTestHarness(
    initialInput: Float,
    initialDirection: InputDirection,
    spec: MotionSpec,
    stableThreshold: Float,
    directionChangeSlop: Float,
    val onFrame: StateFlow<Long>,
    createDerived: (underTest: MotionValue) -> List<MotionValue>,
) : InputScope<MotionValue, DistanceGestureContext> {

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

    override fun updateInput(value: Float) {
        input = value
        gestureContext.dragOffset = value
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

    override fun reset(position: Float, direction: InputDirection) {
        input = position
        gestureContext.reset(position, direction)
    }
}
