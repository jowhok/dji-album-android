package com.wei.djialbum.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wei.djialbum.core.fmtDateTime
import com.wei.djialbum.core.fmtSize
import com.wei.djialbum.model.MediaType
import com.wei.djialbum.model.PurgePreview
import com.wei.djialbum.model.TrashItem
import com.wei.djialbum.ui.AlbumViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: AlbumViewModel, modifier: Modifier = Modifier, bottomPadding: Dp = 0.dp) {
    val trash by vm.trash.collectAsState()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<PurgePreview?>(null) }
    var loadingPreview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(trash) { selected = selected.intersect(trash.map { it.path }.toSet()) }

    Column(modifier = modifier) {
        TopAppBar(
            title = {
                Column {
                    Text("回收站", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${trash.size} 个媒体 · 本地软删除，NAS 原片未动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                TextButton(onClick = {
                    selected = if (selected.size == trash.size && trash.isNotEmpty())
                        emptySet() else trash.map { it.path }.toSet()
                }) { Text("全选") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { vm.restore(selected); selected = emptySet() }
            ) { Text("恢复选中 (${selected.size})") }

            TextButton(
                enabled = selected.isNotEmpty() && !loadingPreview,
                onClick = {
                    if (selected.isEmpty()) return@TextButton
                    loadingPreview = true
                    scope.launch {
                        preview = vm.purgePreview(selected)
                        loadingPreview = false
                    }
                }
            ) {
                Text(
                    if (loadingPreview) "统计中…" else "彻底删除 (${selected.size})",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                trash.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("回收站是空的", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在时光轴长按媒体 → 移入回收站",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(trash, key = { it.path }) { item ->
                        TrashCard(
                            item = item,
                            thumb = vm.thumbFile(item.thumb),
                            selected = item.path in selected,
                            onToggle = {
                                selected = if (item.path in selected) selected - item.path
                                else selected + item.path
                            }
                        )
                    }
                }
            }
        }
    }

    preview?.let { pv ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("彻底删除确认") },
            text = {
                Column {
                    Text(
                        "将从 NAS 真实删除 ${pv.items.size} 个媒体，共 ${pv.totalFiles} 个文件（含同名 .LRF 代理），约 ${fmtSize(pv.totalBytes)}。此操作不可撤销。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    pv.items.take(4).forEach {
                        Text(
                            "· ${it.name}（${it.files.size} 个文件，${fmtSize(it.files.sumOf { f -> f.size })}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pv.items.size > 4) {
                        Text(
                            "…以及另外 ${pv.items.size - 4} 个媒体",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val paths = selected
                    preview = null
                    vm.purge(paths) { selected = emptySet() }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TrashCard(
    item: TrashItem,
    thumb: java.io.File?,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onToggle)
            .padding(bottom = 6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无缩略图", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.type == MediaType.VIDEO) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                )
            }
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
            )
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Text(
                "${fmtSize(item.size)} · ${fmtDateTime(item.deletedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
