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

package com.android.widgetpicker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * A tab (suitable for displaying in a horizontal toolbar) that shows a leading icon along with its
 * label.
 *
 * The icon is shown only when tab is selected (it animates per the provided transition spec.)
 */
@Composable
fun LeadingIconToolbarTab(
    leadingIcon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedBackgroundColor: Color = LeadingIconToolbarTabDefaults.selectedBackgroundColor,
    contentColor: Color = LeadingIconToolbarTabDefaults.contentColor,
    textStyle: TextStyle = LeadingIconToolbarTabDefaults.textStyle,
    iconEnterTransition: EnterTransition = LeadingIconToolbarTabDefaults.iconEnterTransition,
    iconExitTransition: ExitTransition = LeadingIconToolbarTabDefaults.iconExitTransition
) {
    val backgroundColor = if (selected) {
        selectedBackgroundColor
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(color = backgroundColor)
            .minimumInteractiveComponentSize()
            .padding(horizontal = LeadingIconToolbarTabDefaults.horizontalPadding)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        this@Row.AnimatedVisibility(
            visible = selected,
            enter = iconEnterTransition,
            exit = iconExitTransition
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null, // decorative
                modifier = Modifier.padding(end = LeadingIconToolbarTabDefaults.contentSpacing),
                tint = contentColor
            )
        }
        Text(
            text = label,
            style = textStyle,
            color = contentColor
        )
    }
}

/** Holds default values used by the [LeadingIconToolbarTab]. */
object LeadingIconToolbarTabDefaults {
    val selectedBackgroundColor: Color
        @Composable get() = MaterialTheme.colorScheme.secondaryContainer
    val contentColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
    val textStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge

    val horizontalPadding = 16.dp
    val contentSpacing = 8.dp

    val iconEnterTransition: EnterTransition = fadeIn() + expandIn(expandFrom = Alignment.Center)
    val iconExitTransition: ExitTransition = fadeOut() + shrinkOut(shrinkTowards = Alignment.Center)
}
