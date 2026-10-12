// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Page frame with a large title that collapses into the toolbar as the page
 * scrolls, and expands again when it returns to the top. One title source
 * and one scroll state: Material 3's LargeTopAppBar with an
 * exit-until-collapsed behaviour wired to the content's nested scroll, so the
 * title follows the finger and flings exactly and never jumps on a threshold.
 * The bar draws behind the status bar, so collapsed it fills that area too.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.zairenkai.app.R
import com.zairenkai.app.ui.icons.ChevronLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pageTitle")) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.action_back)) }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = behavior,
                modifier = Modifier.testTag("collapsingTopBar"),
            )
        },
        content = content,
    )
}
