package com.junkfood.seal.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.SignalCellular4Bar
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewComfy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.Route

data class Material3SettingsItem(
    val icon: ImageVector,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit = {},
)

@Composable
fun Material3SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    items: List<Material3SettingsItem>,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 4.dp),
            )
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = item.onClick)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            ProvideTextStyle(
                                value = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            ) {
                                item.title()
                            }
                            if (item.description != null) {
                                Spacer(Modifier.height(2.dp))
                                ProvideTextStyle(
                                    value = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                ) {
                                    item.description.invoke()
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        if (item.trailingContent != null) {
                            item.trailingContent.invoke()
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (index < items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                            thickness = Dp.Hairline,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SealSettingsBottomSheet(
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onDismissRequest: () -> Unit,
    onNavigateTo: (String) -> Unit,
) {
    // Keep the sheet strictly below the status bar: the content is tall enough to expand to the
    // full window height, which would otherwise push the header behind the notification icons.
    val windowHeight =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxSheetHeight = (windowHeight - statusBarHeight - 8.dp).coerceAtLeast(0.dp)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(thickness = Dp.Hairline)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Group 1: Downloads & Storage
                item {
                    Material3SettingsGroup(
                        title = "Downloads & Storage",
                        items = listOf(
                            Material3SettingsItem(
                                icon = Icons.Rounded.SettingsApplications,
                                title = { Text(stringResource(R.string.general_settings)) },
                                description = { Text(stringResource(R.string.general_settings_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.GENERAL_DOWNLOAD_PREFERENCES)
                                },
                            ),
                            Material3SettingsItem(
                                icon = Icons.Rounded.Folder,
                                title = { Text(stringResource(R.string.download_directory)) },
                                description = { Text(stringResource(R.string.download_directory_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.DOWNLOAD_DIRECTORY)
                                },
                            ),
                        ),
                    )
                }

                // Group 2: Media & Formats
                item {
                    Material3SettingsGroup(
                        title = "Media & Formats",
                        items = listOf(
                            Material3SettingsItem(
                                icon = Icons.Rounded.VideoFile,
                                title = { Text(stringResource(R.string.format)) },
                                description = { Text(stringResource(R.string.format_settings_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.DOWNLOAD_FORMAT)
                                },
                            ),
                            Material3SettingsItem(
                                icon = Icons.Rounded.Subtitles,
                                title = { Text(stringResource(R.string.subtitle)) },
                                description = { Text(stringResource(R.string.subtitle_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.SUBTITLE_PREFERENCES)
                                },
                            ),
                        ),
                    )
                }

                // Group 3: Appearance & UI
                item {
                    Material3SettingsGroup(
                        title = "Appearance & Interface",
                        items = listOf(
                            Material3SettingsItem(
                                icon = Icons.Rounded.Palette,
                                title = { Text(stringResource(R.string.look_and_feel)) },
                                description = { Text(stringResource(R.string.display_settings)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.APPEARANCE)
                                },
                            ),
                            Material3SettingsItem(
                                icon = Icons.Rounded.ViewComfy,
                                title = { Text(stringResource(R.string.interface_and_interaction)) },
                                description = { Text(stringResource(R.string.settings_before_download)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.INTERACTION)
                                },
                            ),
                        ),
                    )
                }

                // Group 4: Network & Commands
                item {
                    Material3SettingsGroup(
                        title = "Network & Automation",
                        items = listOf(
                            Material3SettingsItem(
                                icon = Icons.Rounded.SignalCellular4Bar,
                                title = { Text(stringResource(R.string.network)) },
                                description = { Text(stringResource(R.string.network_settings_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.NETWORK_PREFERENCES)
                                },
                            ),
                            Material3SettingsItem(
                                icon = Icons.Rounded.Terminal,
                                title = { Text(stringResource(R.string.custom_command)) },
                                description = { Text(stringResource(R.string.custom_command_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.TEMPLATE)
                                },
                            ),
                        ),
                    )
                }

                // Group 5: Troubleshooting & About
                item {
                    Material3SettingsGroup(
                        title = "System & About",
                        items = listOf(
                            Material3SettingsItem(
                                icon = Icons.Rounded.BugReport,
                                title = { Text(stringResource(R.string.trouble_shooting)) },
                                description = { Text(stringResource(R.string.trouble_shooting_desc)) },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.TROUBLESHOOTING)
                                },
                            ),
                            Material3SettingsItem(
                                icon = Icons.Rounded.Info,
                                title = { Text(stringResource(R.string.about)) },
                                description = { Text("App version, developer & links") },
                                onClick = {
                                    onDismissRequest()
                                    onNavigateTo(Route.ABOUT)
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }
}
