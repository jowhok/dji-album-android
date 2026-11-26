package com.wei.djialbum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wei.djialbum.core.fmtSize
import com.wei.djialbum.model.NasConfig
import com.wei.djialbum.ui.AlbumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AlbumViewModel,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null
) {
    val config by vm.config.collectAsState()
    val info by vm.info.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val thumbProgress by vm.thumbProgress.collectAsState()
    val metaProgress by vm.metaProgress.collectAsState()
    val themeMode by vm.themeMode.collectAsState()

    var host by remember(config.host) { mutableStateOf(config.host) }
    var share by remember(config.share) { mutableStateOf(config.share) }
    var rootDir by remember(config.rootDir) { mutableStateOf(config.rootDir) }
    var username by remember(config.username) { mutableStateOf(config.username) }
    var password by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgIsError by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadNasThumbStats() }

    Column(modifier = modifier) {
        TopAppBar(
            title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            msg?.let {
                Text(
                    it,
                    color = if (msgIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            SettingsCard(title = "NAS 连接", subtitle = "SMB 2/3") {
                Text(
                    "当前根目录：${config.host}/${config.share}/${config.rootDir}".trimEnd('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("主机地址") },
                    placeholder = { Text("例如 192.168.1.10 或 NAS 主机名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = share, onValueChange = { share = it },
                    label = { Text("共享名称") },
                    placeholder = { Text("例如 Photos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = rootDir, onValueChange = { rootDir = it },
                    label = { Text("照片根目录（相对共享）") },
                    placeholder = { Text("例如 DCIM/100MEDIA（留空为共享根目录）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("用户名") },
                    placeholder = { Text("SMB 用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码（留空保持原密码）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        msg = null
                        val cfg = NasConfig(host, share, username, rootDir, password)
                        if (!cfg.isValid()) {
                            msg = "主机、共享名、用户名不能为空"
                            msgIsError = true
                            return@OutlinedButton
                        }
                        // 测试使用已保存的密码（输入框为空时）
                        val testCfg = if (password.isEmpty()) cfg.copy(password = config.password) else cfg
                        scope.launch {
                            val r = vm.testConnection(testCfg)
                            msgIsError = r.isFailure
                            msg = if (r.isSuccess) "连接正常，可以访问该目录"
                            else "连接失败：${r.exceptionOrNull()?.message}"
                        }                    }) { Text("测试连接") }

                    Button(onClick = {
                        msg = null
                        val cfg = NasConfig(host, share, username, rootDir, password)
                        vm.saveConfigOnly(cfg) { r ->
                            msgIsError = r.isFailure
                            msg = if (r.isSuccess) "设置已保存；如换了路径，请点下方「重新扫描」"
                            else "保存失败：${r.exceptionOrNull()?.message}"
                            if (r.isSuccess) password = ""
                        }
                    }) { Text("保存设置") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "密码用 Android Keystore 加密后存本机，不写入明文配置文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard(title = "缓存与索引", subtitle = null) {
                val i = info
                if (i == null) {
                    Text("读取中…", style = MaterialTheme.typography.bodySmall)
                } else {
                    val rows = listOf(
                        "媒体总数" to "${i.files}",
                        "视频 / 照片" to "${i.videos} / ${i.photos}",
                        "收藏 / 回收站" to "${i.favorites} / ${i.trash}",
                        "索引总大小" to fmtSize(i.totalBytes),
                        "缩略图缓存" to "${i.cacheFiles} 个 · ${fmtSize(i.cacheBytes)}",
                        "缓存上限" to fmtSize(i.cacheLimitBytes),
                        "待生成缩略图" to "${i.thumbMissing}"
                    )
                    rows.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            pair.forEach { (k, v) ->
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        k,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(v, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                val progress = scanProgress ?: thumbProgress ?: metaProgress
                progress?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "进行中 ${it.done}/${it.total}" + if (it.failed > 0) " · 失败 ${it.failed}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.rescan() }) { Text("重新扫描") }
                    OutlinedButton(onClick = { vm.generateThumbs() }) { Text("重建缩略图") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.generateMeta() }) { Text("提取元数据") }
                    OutlinedButton(onClick = { vm.trimCache() }) { Text("清理超额缓存") }
                    OutlinedButton(onClick = { confirmClear = true }) {
                        Text("清空缓存", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "缩略图缓存只存本地小图，可随时重建；NAS 原片、收藏与回收站记录都不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard(
                title = "NAS 缩略图镜像",
                subtitle = "小图存回 NAS，换设备/重装/清缓存后秒开"
            ) {
                val on by vm.nasThumbsOn.collectAsState()
                val stats by vm.nasThumbStats.collectAsState()
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生成后把缩略图写回 NAS", style = MaterialTheme.typography.bodyMedium)
                        val written = if (stats.second == true) "可写" else if (stats.second == false) "只读，已降级为本地缓存" else "未探测"
                        Text(
                            "远端已有 ${stats.first} 张 · 权限：$written",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = { vm.setNasThumbs(it) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.loadNasThumbStats() }) { Text("重新检测") }
                    OutlinedButton(onClick = { vm.clearNasThumbs() }) { Text("清理 NAS 缩略图") }
                }
            }

            SettingsCard(title = "外观", subtitle = null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("light" to "浅色", "dark" to "深色", "system" to "系统").forEach { (k, label) ->
                        FilterChip(
                            selected = themeMode == k,
                            onClick = { vm.setTheme(k) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            SettingsCard(title = "触屏操作", subtitle = null) {
                listOf(
                    "左右滑动" to "切换上一张 / 下一张",
                    "双指捏合" to "照片缩放（双击复位）",
                    "长按缩略图" to "收藏 / 元数据 / 下载 / 删除 / 多选",
                    "下拉刷新" to "重新扫描 NAS 目录",
                    "点击视频" to "显示或隐藏控制条（3 秒自动隐藏）"
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            k,
                            modifier = Modifier.weight(0.34f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            v,
                            modifier = Modifier.weight(0.66f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsCard(title = "关于", subtitle = null) {
                val i = info
                listOf(
                    "应用版本" to "1.0.0-android",
                    "平台" to "Android · Kotlin + Jetpack Compose",
                    "当前根目录" to (i?.root ?: "—"),
                    "索引" to "本机 SQLite（files / favorites / trash）",
                    "缩略图缓存" to "应用私有目录 thumbs/（512MB 上限）"
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            k,
                            modifier = Modifier.weight(0.34f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(v, modifier = Modifier.weight(0.66f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空缩略图缓存") },
            text = {
                Text(
                    "将删除 ${info?.cacheFiles ?: 0} 个缩略图文件（约 ${fmtSize(info?.cacheBytes ?: 0)}），回到时光轴会自动重建。NAS 原片与索引记录都不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearCache() }) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Spacer(Modifier.height(0.dp))
                    Text(
                        " · $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
