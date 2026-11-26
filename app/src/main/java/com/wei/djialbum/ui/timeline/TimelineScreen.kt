package com.wei.djialbum.ui.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wei.djialbum.core.fmtDate
import com.wei.djialbum.model.MediaItem
import com.wei.djialbum.model.MediaType
import com.wei.djialbum.ui.AlbumViewModel
import com.wei.djialbum.ui.components.DateFilterSheet
import com.wei.djialbum.ui.components.DownloadProgressBar
import com.wei.djialbum.ui.components.ItemActionSheet
import com.wei.djialbum.ui.components.MetaSheet

private val TAB_KEYS = listOf("all", "video", "photo")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    vm: AlbumViewModel,
    favoritesOnly: Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val flow = if (favoritesOnly) vm.favorites else vm.timeline
    val state by flow.collectAsState()
    val selection by vm.selection.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val thumbProgress by vm.thumbProgress.collectAsState()
    val metaProgress by vm.metaProgress.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val banner by vm.banner.collectAsState()

    var forceSelection by remember { mutableStateOf(false) }
    var showDateSheet by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf<MediaItem?>(null) }
    var showMeta by remember { mutableStateOf<String?>(null) }

    val selectionMode = forceSelection || selection.isNotEmpty()

    // rows 里是全部媒体（含视频与照片），各 tab 在本地过滤，切换零延迟
    val base = remember(state.rows, state.date) {
        if (state.date == null) state.rows else state.rows.filter { it.dirDate == state.date }
    }
    val lists = remember(base) {
        mapOf(
            "all" to base,
            "video" to base.filter { it.type == MediaType.VIDEO },
            "photo" to base.filter { it.type == MediaType.PHOTO }
        )
    }
    val counts = remember(state.rows) {
        mapOf(
            "all" to state.rows.size,
            "video" to state.rows.count { it.type == MediaType.VIDEO },
            "photo" to state.rows.count { it.type == MediaType.PHOTO }
        )
    }
    val dates = remember(state.rows) {
        state.rows.groupingBy { it.dirDate }.eachCount().toList().sortedByDescending { it.first }
    }

    // 三个 tab 用 Pager 承载：左右滑动自带跟手动画，点卡片则动画滚过去
    val pagerState = rememberPagerState(
        initialPage = TAB_KEYS.indexOf(state.tab).coerceAtLeast(0),
        pageCount = { TAB_KEYS.size }
    )
    val currentTab by rememberUpdatedState(state.tab)
    LaunchedEffect(pagerState.currentPage) {
        val key = TAB_KEYS[pagerState.currentPage]
        if (currentTab != key) vm.setTab(key, favoritesOnly)
    }
    LaunchedEffect(state.tab) {
        val index = TAB_KEYS.indexOf(state.tab)
        if (index >= 0 && pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }

    Box(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // 一排搞定：胶囊 tab（权重）+ 排序 + 右上角功能按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TAB_KEYS.forEach { key ->
                    TabPill(
                        label = when (key) {
                            "video" -> "视频"
                            "photo" -> "照片"
                            else -> "全部"
                        },
                        count = counts[key] ?: 0,
                        selected = state.tab == key,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setTab(key, favoritesOnly) }
                    )
                }
                SortPill(
                    label = if (state.sort == "size") "大小" else "时间",
                    descending = state.sort == "size",
                    onClick = { vm.setSort(if (state.sort == "size") "time" else "size", favoritesOnly) }
                )
                IconButton(
                    onClick = { showDateSheet = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = "按日期筛选",
                        modifier = Modifier.size(22.dp),
                        tint = if (state.date != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        forceSelection = !forceSelection
                        if (!forceSelection) vm.clearSelection()
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "多选",
                        modifier = Modifier.size(22.dp),
                        tint = if (selectionMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.rescan() }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "重新扫描",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "设置",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            banner?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            it,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { vm.rescan() }) { Text("重试") }
                    }
                }
            }

            if (scanProgress != null || thumbProgress != null || metaProgress != null || downloadProgress != null) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    scanProgress?.let { ProgressLine("扫描 NAS ${it.done}/${it.total}", it.fraction) }
                    thumbProgress?.let {
                        ProgressLine("生成缩略图 ${it.done}/${it.total} · 失败 ${it.failed}", it.fraction)
                    }
                    metaProgress?.let { ProgressLine("提取元数据 ${it.done}/${it.total}", it.fraction) }
                    downloadProgress?.let { (done, total) -> DownloadProgressBar(done, total) }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().weight(1f)
            ) { page ->
                val items = lists[TAB_KEYS[page]].orEmpty()
                PullToRefreshBox(
                    isRefreshing = scanning,
                    onRefresh = { vm.rescan() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        state.error != null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("索引读取失败", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    state.error ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { vm.refreshTimeline(scan = false) }) {
                                    Text("重试")
                                }
                            }
                        }

                        items.isEmpty() -> EmptyState(
                            favoritesOnly = favoritesOnly,
                            dateFiltered = state.date != null,
                            tabKey = TAB_KEYS[page]
                        )

                        else -> MediaGrid(
                            items = items,
                            vm = vm,
                            selection = selection,
                            selectionMode = selectionMode,
                            bottomPadding = bottomPadding,
                            onOpen = onOpen,
                            onLongClick = { showActions = it }
                        )
                    }
                }
            }
        }

        if (selectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomPadding)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        "已选 ${selection.size} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { vm.setFavoriteSelection(true) }) { Text("收藏") }
                        TextButton(onClick = { vm.setFavoriteSelection(false) }) { Text("取消收藏") }
                        TextButton(onClick = {
                            vm.selectAll(lists[state.tab].orEmpty().map { it.path })
                        }) { Text("全选") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            if (selection.isEmpty()) {
                                forceSelection = false
                            } else {
                                vm.moveSelectionToTrash()
                            }
                        }) {
                            Text(
                                if (selection.isEmpty()) "完成" else "移入回收站",
                                color = if (selection.isEmpty()) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    showActions?.let { item ->
        ItemActionSheet(
            item = item,
            onDismiss = { showActions = null },
            onFavorite = { vm.toggleFavorite(item); showActions = null },
            onMeta = { showMeta = item.path; showActions = null },
            onDownload = { vm.download(item); showActions = null },
            onTrash = { vm.moveToTrash(item); showActions = null },
            onSelect = {
                forceSelection = true
                vm.toggleSelection(item.path)
                showActions = null
            }
        )
    }

    showMeta?.let { path -> MetaSheet(vm, path, onDismiss = { showMeta = null }) }

    if (showDateSheet) {
        DateFilterSheet(
            dates = dates,
            selected = state.date,
            onSelect = { date -> vm.setDate(date, favoritesOnly) },
            onDismiss = { showDateSheet = false }
        )
    }
}

/** 单个 tab 的网格，等大正方形格子；横竖构图都占同样的位置 */
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    vm: AlbumViewModel,
    selection: Set<String>,
    selectionMode: Boolean,
    bottomPadding: Dp,
    onOpen: (String) -> Unit,
    onLongClick: (MediaItem) -> Unit
) {
    val groups: List<Pair<String, List<MediaItem>>> = remember(items) {
        val list = mutableListOf<Pair<String, MutableList<MediaItem>>>()
        items.forEach { item ->
            val last = list.lastOrNull()
            if (last != null && last.first == item.dirDate) last.second.add(item)
            else list.add(item.dirDate to mutableListOf(item))
        }
        list.map { it.first to it.second.toList() }
    }
    val gridState = rememberLazyGridState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 6.dp
        val side = 12.dp
        val targetCell = if (maxWidth < 600.dp) 108.dp else 148.dp
        val available = maxWidth - side * 2
        val cols = ((available / targetCell).toInt()).coerceIn(3, 12)
        // 显式算出正方形边长，保证横竖片占用完全相同的格子
        val cell = ((available - gap * (cols - 1)) / cols).coerceAtLeast(64.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            contentPadding = PaddingValues(
                start = side, end = side, top = 2.dp,
                bottom = bottomPadding + if (selectionMode) 96.dp else 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize()
        ) {
            groups.forEach { (date, itemsInDate) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "head-$date") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            fmtDate(date),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${itemsInDate.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(itemsInDate, key = { it.path }) { item ->
                    MediaTile(
                        item = item,
                        thumb = vm.thumbFile(item.thumb),
                        modifier = Modifier.size(cell),
                        selected = item.path in selection,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) vm.toggleSelection(item.path)
                            else onOpen(item.path)
                        },
                        onLongClick = {
                            if (selectionMode) vm.toggleSelection(item.path)
                            else onLongClick(item)
                        }
                    )
                }
            }
        }
    }
}

/** 顶部小胶囊：文字 + 数量在同一行，高度只有 34dp，尽量少占地方 */
@Composable
private fun TabPill(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = if (selected) 0.85f else 0.62f),
            maxLines = 1
        )
    }
}

@Composable
private fun SortPill(
    label: String,
    descending: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ProgressLine(text: String, fraction: Float) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp)
        )
    }
}

@Composable
private fun EmptyState(favoritesOnly: Boolean, dateFiltered: Boolean, tabKey: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    dateFiltered -> "该日期暂无媒体"
                    favoritesOnly -> "还没有收藏的媒体"
                    tabKey == "video" -> "暂无视频"
                    tabKey == "photo" -> "暂无照片"
                    else -> "暂无媒体"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    dateFiltered -> "点右上角日历按钮可切回全部日期"
                    favoritesOnly -> "长按任意媒体即可加入收藏"
                    else -> "下拉刷新或到设置里「重新扫描」"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
