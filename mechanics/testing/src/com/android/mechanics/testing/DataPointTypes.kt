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

package com.android.mechanics.testing

import com.android.mechanics.spring.SpringParameters
import com.android.mechanics.testing.DataPointTypes.springParameters
import org.json.JSONObject
import platform.test.motion.golden.DataPointType
import platform.test.motion.golden.UnknownTypeException

fun SpringParameters.asDataPoint() = springParameters.makeDataPoint(this)

object DataPointTypes {
    val springParameters: DataPointType<SpringParameters> =
        DataPointType(
            "springParameters",
            jsonToValue = {
                with(it as? JSONObject ?: throw UnknownTypeException()) {
                    SpringParameters(
                        getDouble("stiffness").toFloat(),
                        getDouble("dampingRatio").toFloat(),
                    )
                }
            },
            valueToJson = {
                JSONObject().apply {
                    put("stiffness", it.stiffness)
                    put("dampingRatio", it.dampingRatio)
                }
            },
        )
}
