package com.junkfood.seal.ui.page.videolist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.CheckBoxItem
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.junkfood.seal.ui.component.MediaListItem
import com.junkfood.seal.ui.component.SealDialog
import com.junkfood.seal.ui.component.SealSearchBar
import com.junkfood.seal.ui.component.VideoFilterChip
import com.junkfood.seal.ui.svg.DynamicColorImageVectors
import com.junkfood.seal.ui.svg.drawablevectors.videoSteaming
import com.junkfood.seal.util.AUDIO_REGEX
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.toFileSizeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

fun DownloadedVideoInfo.filterByType(
    videoFilter: Boolean = false,
    audioFilter: Boolean = true,
): Boolean {
    return if (!(videoFilter || audioFilter)) true
    else if (audioFilter) this.videoPath.contains(Regex(AUDIO_REGEX))
    else !this.videoPath.contains(Regex(AUDIO_REGEX))
}

fun DownloadedVideoInfo.filterSort(
    viewState: VideoListViewModel.VideoListViewState,
    filterSet: Set<String>,
): Boolean {
    return filterByType(videoFilter = viewState.videoFilter, audioFilter = viewState.audioFilter) &&
        filterByExtractor(filterSet.elementAtOrNull(viewState.activeFilterIndex))
}

fun DownloadedVideoInfo.filterByExtractor(extractor: String?): Boolean {
    return extractor.isNullOrEmpty() || (this.extractor == extractor)
}

private const val TAG = "VideoListPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListPage(
    modifier: Modifier = Modifier,
    viewModel: VideoListViewModel = koinViewModel(),
    showTopBar: Boolean = true,
    onNavigateBack: () -> Unit = {},
) {
    val viewState by viewModel.stateFlow.collectAsStateWithLifecycle()
    val fullVideoList by viewModel.videoListFlow.collectAsStateWithLifecycle(emptyList())
    val searchedVideoList by
        viewModel.searchedVideoListFlow.collectAsStateWithLifecycle(emptyList())

    val videoList = if (viewState.isSearching) searchedVideoList else fullVideoList
    val filterSet by viewModel.filterSetFlow.collectAsState(mutableSetOf())

    val scrollBehavior =
        if (fullVideoList.isNotEmpty())
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
                rememberTopAppBarState(),
                canScroll = { true },
            )
        else TopAppBarDefaults.pinnedScrollBehavior()

    val scope = rememberCoroutineScope()
    val softKeyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current

    val fileSizeMap by
        viewModel.fileSizeMapFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
    val hostState = remember { SnackbarHostState() }

    var currentVideoInfo by remember { mutableStateOf(DownloadedVideoInfo()) }

    var isSelectEnabled by remember { mutableStateOf(false) }
    var showRemoveMultipleItemsDialog by remember { mutableStateOf(false) }

    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var deleteHistoryFiles by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    @Composable
    fun FilterChips(modifier: Modifier = Modifier) {
        Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                VideoFilterChip(
                    selected = viewState.audioFilter,
                    onClick = { viewModel.clickAudioFilter() },
                    label = stringResource(id = R.string.audio),
                )

                VideoFilterChip(
                    selected = viewState.videoFilter,
                    onClick = { viewModel.clickVideoFilter() },
                    label = stringResource(id = R.string.video),
                )
                if (filterSet.size > 1) {
                    VerticalDivider(
                        modifier =
                            Modifier.padding(horizontal = 6.dp)
                                .height(24.dp)
                                .width(1f.dp)
                                .align(Alignment.CenterVertically),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    for (i in 0 until filterSet.size) {
                        VideoFilterChip(
                            selected = viewState.activeFilterIndex == i,
                            onClick = { viewModel.clickExtractorFilter(i) },
                            label = filterSet.elementAt(i),
                        )
                    }
                }
            }
        }
    }

    val selectedItemIds = remember(videoList, viewState) { mutableStateListOf<Int>() }

    LaunchedEffect(isSelectEnabled) {
        if (!isSelectEnabled) {
            delay(200)
            selectedItemIds.clear()
        }
    }

    val selectedVideoCount =
        remember(selectedItemIds.size) {
            mutableIntStateOf(
                videoList.count { info ->
                    selectedItemIds.contains(info.id) &&
                        info.filterByType(videoFilter = true, audioFilter = false)
                }
            )
        }
    val selectedAudioCount =
        remember(selectedItemIds.size) {
            mutableIntStateOf(
                videoList.count { info ->
                    selectedItemIds.contains(info.id) &&
                        info.filterByType(videoFilter = false, audioFilter = true)
                }
            )
        }

    val selectedFileSizeSum by
        remember(selectedItemIds.size) {
            derivedStateOf {
                selectedItemIds.fold(0L) { acc: Long, id: Int ->
                    acc + fileSizeMap.getOrElse(id) { 0L }
                }
            }
        }

    val visibleItemCount =
        remember(videoList, viewState) {
            mutableIntStateOf(videoList.count { it.filterSort(viewState, filterSet) })
        }

    val checkBoxState by
        remember(selectedItemIds, visibleItemCount) {
            derivedStateOf {
                if (selectedItemIds.isEmpty()) ToggleableState.Off
                else if (
                    selectedItemIds.size == visibleItemCount.intValue &&
                        selectedItemIds.isNotEmpty()
                )
                    ToggleableState.On
                else ToggleableState.Indeterminate
            }
        }

    var showRemoveDialog by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }

    BackHandler(isSelectEnabled || viewState.isSearching) {
        if (isSelectEnabled) {
            isSelectEnabled = false
        } else {
            viewModel.toggleSearch(false)
        }
    }

    LaunchedEffect(sheetState.targetValue, isSelectEnabled) {
        if (showBottomSheet || isSelectEnabled) {
            softKeyboardController?.hide()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (showTopBar) {
                LargeTopAppBar(
                title = {
                    Text(modifier = Modifier, text = stringResource(R.string.downloads_history))
                },
                navigationIcon = { BackButton { onNavigateBack() } },
                actions = {
                    Row {
                        if (fullVideoList.isNotEmpty()) {
                            IconToggleButton(
                                modifier = Modifier,
                                onCheckedChange = {
                                    view.slightHapticFeedback()
                                    viewModel.toggleSearch(it)
                                    if (it) {
                                        scope.launch {
                                            delay(50)
                                            lazyListState.animateScrollToItem(0)
                                        }
                                    }
                                },
                                checked = viewState.isSearching,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.search),
                                )
                            }
                            var expanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                                IconButton(onClick = { expanded = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription =
                                            stringResource(id = R.string.show_more_actions),
                                    )
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteSweep,
                                                contentDescription = null,
                                            )
                                        },
                                        text = {
                                            Text(text = stringResource(id = R.string.clear_history))
                                        },
                                        onClick = {
                                            showClearHistoryDialog = true
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                isSelectEnabled,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                BottomAppBar(modifier = Modifier) {
                    val selectAllText = stringResource(R.string.select_all)
                    TriStateCheckbox(
                        modifier = Modifier.semantics { this.contentDescription = selectAllText },
                        state = checkBoxState,
                        onClick = {
                            view.slightHapticFeedback()
                            when (checkBoxState) {
                                ToggleableState.On -> selectedItemIds.clear()
                                else -> {
                                    for (item in videoList) {
                                        if (
                                            !selectedItemIds.contains(item.id) &&
                                                item.filterSort(viewState, filterSet)
                                        ) {
                                            selectedItemIds.add(item.id)
                                        }
                                    }
                                }
                            }
                        },
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text =
                            stringResource(R.string.multiselect_item_count)
                                .format(selectedVideoCount.intValue, selectedAudioCount.intValue),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(
                        onClick = {
                            view.slightHapticFeedback()
                            showRemoveMultipleItemsDialog = true
                        },
                        enabled = selectedItemIds.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(id = R.string.remove),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = hostState) },
    ) { innerPadding ->
        if (fullVideoList.isEmpty())
            Box(modifier = Modifier.fillMaxSize()) {
                val painter =
                    rememberVectorPainter(image = DynamicColorImageVectors.videoSteaming())
                Column(
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier =
                            Modifier.padding(vertical = 20.dp)
                                .fillMaxWidth(0.5f)
                                .widthIn(max = 240.dp),
                    )
                    Text(
                        text = stringResource(R.string.no_downloaded_media),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        val cellCount =
            when (LocalWindowWidthState.current) {
                WindowWidthSizeClass.Expanded -> 2
                else -> 1
            }
        val span: (LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(cellCount) }
        LazyColumn(modifier = Modifier, state = lazyListState, contentPadding = innerPadding) {
            if (fullVideoList.isNotEmpty()) {
                item {
                    Column {
                        if (!showTopBar) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconToggleButton(
                                    modifier = Modifier,
                                    onCheckedChange = {
                                        view.slightHapticFeedback()
                                        viewModel.toggleSearch(it)
                                        if (it) {
                                            scope.launch {
                                                delay(50)
                                                lazyListState.animateScrollToItem(0)
                                            }
                                        }
                                    },
                                    checked = viewState.isSearching,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = stringResource(R.string.search),
                                    )
                                }
                                var expanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                                    IconButton(onClick = { expanded = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.MoreVert,
                                            contentDescription =
                                                stringResource(id = R.string.show_more_actions),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.DeleteSweep,
                                                    contentDescription = null,
                                                )
                                            },
                                            text = {
                                                Text(text = stringResource(id = R.string.clear_history))
                                            },
                                            onClick = {
                                                showClearHistoryDialog = true
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        AnimatedVisibility(visible = viewState.isSearching) {
                            SealSearchBar(
                                modifier =
                                    Modifier.padding(horizontal = 12.dp).padding(vertical = 8.dp),
                                text = viewState.searchText,
                                placeholderText = stringResource(R.string.search_in_downloads),
                                onValueChange = viewModel::updateSearchText,
                            )
                        }
                        FilterChips(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
            }
            for (info in videoList) {

                item(key = info.id, contentType = { info.videoPath.contains(AUDIO_REGEX) }) {
                    with(info) {
                        AnimatedVisibility(
                            modifier = Modifier,
                            visible = info.filterSort(viewState, filterSet),
                            exit = shrinkVertically() + fadeOut(),
                            enter = expandVertically() + fadeIn(),
                        ) {
                            MediaListItem(
                                modifier = Modifier,
                                title = videoTitle,
                                author = videoAuthor,
                                thumbnailUrl = thumbnailUrl,
                                videoPath = videoPath,
                                videoFileSize = fileSizeMap.getOrElse(id) { 0L },
                                videoUrl = videoUrl,
                                isSelectEnabled = { isSelectEnabled },
                                isSelected = { selectedItemIds.contains(id) },
                                onSelect = {
                                    if (selectedItemIds.contains(id)) selectedItemIds.remove(id)
                                    else selectedItemIds.add(id)
                                },
                                onClick = {
                                    FileUtil.openFile(path = videoPath) {
                                        ToastUtil.makeToastSuspend(
                                            App.context.getString(R.string.file_unavailable)
                                        )
                                    }
                                },
                                onLongClick = {
                                    isSelectEnabled = true
                                    selectedItemIds.add(id)
                                },
                                onShowContextMenu = {
                                    view.slightHapticFeedback()
                                    currentVideoInfo = info
                                    scope.launch {
                                        showBottomSheet = true
                                        delay(50)
                                        sheetState.show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBottomSheet) {
        val isFileAvailable = fileSizeMap[currentVideoInfo.id] != 0L
        VideoDetailDrawer(
            sheetState = sheetState,
            info = currentVideoInfo,
            isFileAvailable = isFileAvailable,
            onDismissRequest = {
                scope.launch { sheetState.hide() }.invokeOnCompletion { showBottomSheet = false }
            },
            onDelete = { showRemoveDialog = true },
        )
    }

    var deleteFile by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        RemoveItemDialog(
            info = currentVideoInfo,
            deleteFile = deleteFile,
            onDeleteFileToggled = { deleteFile = it },
            onRemoveConfirm = {
                viewModel.deleteDownloadHistory(listOf(currentVideoInfo), deleteFile = deleteFile)
            },
            onDismissRequest = { showRemoveDialog = false },
        )
    }

    if (showRemoveMultipleItemsDialog) {
        SealDialog(
            onDismissRequest = { showRemoveMultipleItemsDialog = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text(stringResource(R.string.delete_info)) },
            text = {
                Column {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        text =
                            stringResource(R.string.delete_multiple_items_msg)
                                .format(selectedItemIds.size),
                    )
                    CheckBoxItem(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        text =
                            stringResource(R.string.delete_file) +
                                " (${selectedFileSizeSum.toFileSizeText()})",
                        checked = deleteFile,
                    ) {
                        deleteFile = !deleteFile
                    }
                }
            },
            confirmButton = {
                ConfirmButton {
                    viewModel.deleteDownloadHistory(
                        infoList = videoList.filter { selectedItemIds.contains(it.id) },
                        deleteFile = deleteFile,
                    )
                    showRemoveMultipleItemsDialog = false
                    isSelectEnabled = false
                }
            },
            dismissButton = { DismissButton { showRemoveMultipleItemsDialog = false } },
        )
    }

    if (showClearHistoryDialog) {
        SealDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text(stringResource(R.string.clear_history)) },
            text = {
                Column {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        text = stringResource(R.string.clear_history_msg),
                    )
                    CheckBoxItem(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        text = stringResource(R.string.delete_file),
                        checked = deleteHistoryFiles,
                    ) {
                        deleteHistoryFiles = !deleteHistoryFiles
                    }
                }
            },
            confirmButton = {
                ConfirmButton {
                    viewModel.clearAllHistory(deleteFiles = deleteHistoryFiles)
                    selectedItemIds.clear()
                    isSelectEnabled = false
                    if (viewState.isSearching) {
                        viewModel.toggleSearch(false)
                    }
                    showClearHistoryDialog = false
                    ToastUtil.makeToast(R.string.download_history_cleared)
                }
            },
            dismissButton = { DismissButton { showClearHistoryDialog = false } },
        )
    }
}
