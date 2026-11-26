package com.wei.djialbum.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wei.djialbum.core.fmtDate
import com.wei.djialbum.core.fmtDuration
import com.wei.djialbum.core.fmtSize
import com.wei.djialbum.model.MediaItem
import com.wei.djialbum.model.MediaMeta
import com.wei.djialbum.model.MediaType
import com.wei.djialbum.ui.AlbumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onMeta: () -> Unit,
    onDownload: () -> Unit,
    onTrash: () -> Unit,
    onSelect: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${fmtDate(item.dirDate)} · ${fmtSize(item.size)}" +
                        if (item.duration != null) " · ${fmtDuration(item.duration)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SheetAction(
                Icons.Outlined.FavoriteBorder,
                if (item.fav) "取消收藏" else "加入收藏",
                onFavorite
            )
            SheetAction(Icons.Outlined.Info, "查看元数据", onMeta)
            SheetAction(Icons.Outlined.Download, "下载原片", onDownload)
            SheetAction(Icons.Outlined.Checklist, "多选", onSelect)
            SheetAction(Icons.Outlined.DeleteOutline, "移入回收站", onTrash, danger = true)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetaSheet(vm: AlbumViewModel, path: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var meta by remember { mutableStateOf<MediaMeta?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        runCatching { vm.readMeta(path) }
            .onSuccess { meta = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Text("元数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                meta == null -> Text("无法读取元数据")
                else -> MetaRows(meta!!)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetaRows(m: MediaMeta) {
    val rows = listOf(
        "文件名" to m.name,
        "类型" to if (m.type == MediaType.VIDEO) "视频" else "照片",
        "拍摄日期" to fmtDate(m.dirDate),
        "文件大小" to fmtSize(m.size),
        "分辨率" to if (m.width != null && m.height != null) "${m.width} × ${m.height}" else "—",
        "时长" to (if (m.duration != null) fmtDuration(m.duration) else "—"),
        "码率" to (if (m.bitrate != null) "${m.bitrate / 1000} kbps" else "—"),
        "编码" to (m.vcodec ?: "—"),
        "LRF 代理" to (m.lrfPath?.let { fmtSize(m.lrfSize ?: 0L) } ?: "无"),
        "NAS 上存在" to if (m.exists) "是" else "否（已被外部删除）"
    )
    Column {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    k,
                    modifier = Modifier.width(92.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFilterSheet(
    dates: List<Pair<String, Int>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var collapsedMonth by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "按日期筛选",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onSelect(null); onDismiss() }) {
                    Text(if (selected == null) "全部日期" else "清除筛选")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                dates.groupBy { it.first.take(6) }.forEach { (month, monthDates) ->
                    item(key = "m-$month") {
                        Text(
                            monthLabel(month),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    collapsedMonth = if (collapsedMonth == month) null else month
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                    if (collapsedMonth != month) {
                        items(monthDates, key = { "d-${it.first}" }) { (date, count) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(date); onDismiss() }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    fmtDate(date),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (date == selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "$count 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun monthLabel(month: String): String =
    if (month.length == 6) "${month.substring(0, 4)} 年 ${month.substring(4, 6)} 月" else month

@Composable
fun DownloadProgressBar(done: Long, total: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "下载中 ${fmtSize(done)} / ${fmtSize(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else (done.toFloat() / total) },
            modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp)
        )
    }
}
