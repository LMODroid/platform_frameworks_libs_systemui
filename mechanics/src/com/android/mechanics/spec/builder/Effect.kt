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

/**
 * Blueprint for a reusable behavior in a [MotionSpec].
 *
 * [Effect] instances are reusable for building multiple
 */
interface Effect {
    /**
     * Defines the intrinsic length of the effect.
     *
     * The return value takes precedent over the [effectPlacement]. By default, the result is
     * derived from the [effectPlacement].
     *
     * Returning `Float.POSITIVE/NEGATIVE_INFINITY` will cause the effect to extend to the start of
     * the next effect, or the boundary of the effect.
     */
    fun MotionBuilderContext.measure(effectPlacement: EffectPlacement): Float {
        return if (effectPlacement.end.isFinite()) {
            effectPlacement.end - effectPlacement.start
        } else {
            effectPlacement.end
        }
    }

    /**
     * Applies the effect to the motion spec.
     *
     * The boundaries of the effect are defined by the [EffectApplyScope.minLimit] and
     * [EffectApplyScope.maxLimit] properties, and extend in both, the min and max direction by the
     * same amount.
     *
     * Implementations must invoke either [EffectApplyScope.unidirectional] or both,
     * [EffectApplyScope.forward] and [EffectApplyScope.backward]. The motion spec builder will
     * throw if neither is called.
     */
    fun EffectApplyScope.createSpec()
}

/**
 * Handle for an [Effect] that was placed within a [MotionSpecBuilderScope].
 *
 * Used to place effects relative to each other.
 */
@JvmInline value class PlacedEffect internal constructor(internal val id: Int)
