/*
 * Copyright 2024 The Android Open Source Project
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

// Copied unchanged from material-icons-extended 1.7.3 except for the
// package line; see README.md in this folder.

package org.gilune.app.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.AudioFile: ImageVector
    get() {
        if (_audioFile != null) {
            return _audioFile!!
        }
        _audioFile = materialIcon(name = "Outlined.AudioFile") {
            materialPath {
                moveTo(14.0f, 2.0f)
                horizontalLineTo(6.0f)
                curveTo(4.9f, 2.0f, 4.0f, 2.9f, 4.0f, 4.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(8.0f)
                lineTo(14.0f, 2.0f)
                close()
                moveTo(6.0f, 20.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(11.0f)
                horizontalLineTo(6.0f)
                close()
                moveTo(16.0f, 11.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(3.88f)
                curveToRelative(-0.36f, -0.24f, -0.79f, -0.38f, -1.25f, -0.38f)
                curveToRelative(-1.24f, 0.0f, -2.25f, 1.01f, -2.25f, 2.25f)
                curveToRelative(0.0f, 1.24f, 1.01f, 2.25f, 2.25f, 2.25f)
                reflectiveCurveTo(13.0f, 17.99f, 13.0f, 16.75f)
                verticalLineTo(13.0f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(11.0f)
                close()
            }
        }
        return _audioFile!!
    }

private var _audioFile: ImageVector? = null
