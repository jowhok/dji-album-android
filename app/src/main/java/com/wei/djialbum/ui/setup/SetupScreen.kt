package com.wei.djialbum.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.wei.djialbum.model.NasConfig
import com.wei.djialbum.ui.AlbumViewModel
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(vm: AlbumViewModel, modifier: Modifier = Modifier) {
    val config by vm.config.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(config.host) }
    var share by remember { mutableStateOf(config.share) }
    var rootDir by remember { mutableStateOf(config.rootDir) }
    var username by remember { mutableStateOf(config.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun current(): NasConfig = NasConfig(host, share, username, rootDir, password)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("DJI 相册", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "在手机上直接浏览 NAS 里的 Pocket 素材：SMB 连接、.LRF 流畅预览、触屏优先的瀑布流与播放器。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("连接 NAS", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("主机地址") },
                    placeholder = { Text("例如 192.168.1.10 或 NAS 主机名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = share, onValueChange = { share = it },
                    label = { Text("共享名称") },
                    placeholder = { Text("例如 Photos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = rootDir, onValueChange = { rootDir = it },
                    label = { Text("照片根目录（相对共享）") },
                    placeholder = { Text("例如 DCIM/100MEDIA（留空为共享根目录）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("用户名") },
                    placeholder = { Text("SMB 用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                info?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }

                scanProgress?.let { p ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "正在扫描 ${p.done}/${p.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            error = null; info = null; busy = true
                            scope.launch {
                                val r = vm.testConnection(current())
                                busy = false
                                if (r.isSuccess) info = "连接正常，可以访问该目录"
                                else error = r.exceptionOrNull()?.message ?: "连接失败"
                            }
                        }
                    ) { Text("测试连接") }

                    Button(
                        enabled = !busy,
                        onClick = {
                            error = null; info = null
                            if (!current().isValid()) {
                                error = "主机、共享名、用户名不能为空"
                                return@Button
                            }
                            busy = true
                            scope.launch {
                                val r = vm.saveAndScan(current())
                                busy = false
                                if (r.isFailure) {
                                    error = r.exceptionOrNull()?.message ?: "保存失败"
                                } else {
                                    info = "连接成功，正在进入时光轴"
                                }
                            }
                        }
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(if (busy) "处理中…" else "保存并扫描")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "密码使用 Android Keystore 加密后保存在本机，不会明文落盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
