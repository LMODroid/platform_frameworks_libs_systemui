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

package com.android.mechanics.effects

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.effects.MagneticDetach.Defaults.AttachPosition
import com.android.mechanics.effects.MagneticDetach.Defaults.DetachPosition
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.builder.MotionBuilderContext
import com.android.mechanics.spec.builder.spatialMotionSpec
import com.android.mechanics.testing.ComposeMotionValueToolkit
import com.android.mechanics.testing.FakeMotionSpecBuilderContext
import com.android.mechanics.testing.MotionSpecSubject.Companion.assertThat
import com.android.mechanics.testing.animateValueTo
import com.android.mechanics.testing.goldenTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.testing.createGoldenPathManager

@RunWith(AndroidJUnit4::class)
class MagneticDetachTest : MotionBuilderContext by FakeMotionSpecBuilderContext.Default {

    private val goldenPathManager =
        createGoldenPathManager("frameworks/libs/systemui/mechanics/tests/goldens")

    @get:Rule val motion = MotionTestRule(ComposeMotionValueToolkit, goldenPathManager)

    @Test
    fun magneticDetach_matchesSpec() {
        val underTests = spatialMotionSpec { after(10f, MagneticDetach()) }

        assertThat(underTests).maxDirection().breakpoints().positions().containsExactly(10f, 90f)
        assertThat(underTests)
            .minDirection()
            .breakpoints()
            .positions()
            .containsExactly(10f, 50f, 90f)
    }

    @Test
    fun magneticDetach_detach_animatesDetach() {
        motion.goldenTest(spatialMotionSpec { after(10f, MagneticDetach()) }) {
            animateValueTo(DetachPosition.toPx() + 10f, changePerFrame = 5f)
            awaitStable()
        }
    }

    @Test
    fun magneticDetach_attach_snapsToOrigin() {
        val effect = MagneticDetach()
        motion.goldenTest(
            spatialMotionSpec { after(10f, effect) },
            initialValue = DetachPosition.toPx() + 20f,
            initialDirection = InputDirection.Min,
        ) {
            animateValueTo(0f, changePerFrame = 5f)
            awaitStable()
        }
    }

    @Test
    fun magneticDetach_beforeAttach_suppressesDirectionReverse() {
        motion.goldenTest(
            spatialMotionSpec { after(10f, MagneticDetach()) },
            initialValue = DetachPosition.toPx() + 20f,
            initialDirection = InputDirection.Min,
        ) {
            animateValueTo(AttachPosition.toPx() + 11f)
            animateValueTo(DetachPosition.toPx() + 20f)
            awaitStable()
        }
    }

    @Test
    fun magneticDetach_afterAttach_detachesAgain() {
        motion.goldenTest(
            spatialMotionSpec { after(10f, MagneticDetach()) },
            initialValue = DetachPosition.toPx() + 20f,
            initialDirection = InputDirection.Min,
        ) {
            animateValueTo(AttachPosition.toPx() / 2f + 10f, changePerFrame = 5f)
            awaitStable()
            animateValueTo(DetachPosition.toPx() + 20f, changePerFrame = 5f)
            awaitStable()
        }
    }

    @Test
    fun magneticDetach_beforeDetach_suppressesDirectionReverse() {
        motion.goldenTest(spatialMotionSpec { after(10f, MagneticDetach()) }) {
            animateValueTo(DetachPosition.toPx() - 9f)
            animateValueTo(0f)
            awaitStable()
        }
    }

    @Test
    fun magneticDetach_placedWithDifferentBaseMapping() {
        motion.goldenTest(
            spatialMotionSpec(baseMapping = Mapping.Linear(factor = -10f)) {
                after(-10f, MagneticDetach())
            },
            initialValue = -10f,
        ) {
            animateValueTo(DetachPosition.toPx() - 10f)
            awaitStable()
        }
    }
}
