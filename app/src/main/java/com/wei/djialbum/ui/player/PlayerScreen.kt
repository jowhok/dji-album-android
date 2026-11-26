package com.wei.djialbum.ui.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.wei.djialbum.core.fmtDuration
import com.wei.djialbum.core.fmtSize
import com.wei.djialbum.model.MediaItem
import com.wei.djialbum.model.MediaType
import com.wei.djialbum.player.SmbDataSourceFactory
import com.wei.djialbum.ui.AlbumViewModel
import com.wei.djialbum.ui.components.ItemActionSheet
import com.wei.djialbum.ui.components.MetaSheet
import com.wei.djialbum.ui.theme.DjiColors
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as ExoMediaItem

@Composable
fun PlayerScreen(
    vm: AlbumViewModel,
    rows: List<MediaItem>,
    startPath: String,
    onClose: () -> Unit
) {
    val list = remember(rows, startPath) {
        if (rows.any { it.path == startPath }) rows
        else rows + MediaItem(
            path = startPath,
            name = startPath.substringAfterLast('/'),
            type = MediaType.VIDEO,
            dirDate = "",
            size = 0,
            takenAt = 0,
            thumb = null,
            lrfPath = null,
            fav = false,
            duration = null
        )
    }
    val startIndex = list.indexOfFirst { it.path == startPath }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { list.size })
    val current = list.getOrNull(pagerState.currentPage) ?: return

    val context = LocalContext.current

    var showControls by remember { mutableStateOf(true) }
    var showActions by remember { mutableStateOf(false) }
    var showMeta by remember { mutableStateOf(false) }
    var photoZoomed by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("smooth") }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(SmbDataSourceFactory { vm.smb })
            )
            // NAS 带宽有限且抖动大，把缓冲拉长，避免解码器"吃完一帧等一块"
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        20_000,  // 最少缓冲 20s 才开播
                        120_000, // 最多缓冲 120s（字节上限会兜底）
                        2_500,   // 起播前缓冲
                        8_000    // 卡顿后重新缓冲
                    )
                    .build()
            )
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                playing = isPlayingNow
            }

            override fun onPlaybackStateChanged(state: Int) {
                durationMs = player.duration.takeIf { it > 0 } ?: 0L
                if (state == Player.STATE_READY) playerError = null
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = error.message ?: "播放失败"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 全屏沉浸
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler { onClose() }

    // 切页 / 切清晰度时更新播放源
    LaunchedEffect(current.path, quality) {
        playerError = null
        if (current.type == MediaType.VIDEO) {
            val source = if (quality == "smooth") (current.lrfPath ?: current.path) else current.path
            val uri = Uri.parse("dji-smb://media").buildUpon()
                .appendQueryParameter("p", source).build()
            player.setMediaItem(
                ExoMediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            )
            player.prepare()
            player.playWhenReady = true
            photoZoomed = false
        } else {
            player.stop()
            player.clearMediaItems()
            playing = false
        }
        positionMs = 0
    }

    LaunchedEffect(playing, current.path) {
        while (playing) {
            positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it > 0 } ?: durationMs
            delay(500)
        }
    }

    LaunchedEffect(showControls, playing, current.path) {
        if (showControls && playing && current.type == MediaType.VIDEO) {
            delay(3000)
            showControls = false
        }
    }

    // 下滑返回：跟手位移 + 释放后回弹或退出
    var dragY by remember { mutableStateOf(0f) }
    val dragAnim by animateFloatAsState(
        targetValue = dragY,
        animationSpec = tween(durationMillis = if (dragY == 0f) 260 else 60),
        label = "dragY"
    )
    val dismissThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                translationY = dragAnim
                val h = size.height.coerceAtLeast(1f)
                alpha = (1f - dragAnim / (h / 2f)).coerceIn(0.25f, 1f)
            }
            .pointerInput(photoZoomed, current.path) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, delta ->
                        if (!photoZoomed) {
                            dragY = (dragY + delta).coerceAtLeast(0f)
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (dragY > dismissThreshold) onClose() else dragY = 0f
                    },
                    onDragCancel = { dragY = 0f }
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !photoZoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = list.getOrNull(page) ?: return@HorizontalPager
            if (item.type == MediaType.PHOTO) {
                PhotoPage(
                    vm = vm,
                    item = item,
                    active = page == pagerState.currentPage,
                    onZoomChange = { photoZoomed = it },
                    onTap = { showControls = !showControls }
                )
            } else {
                VideoPage(
                    player = player,
                    active = page == pagerState.currentPage,
                    onTap = { showControls = !showControls }
                )
            }
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xCC000000),
                            1f to Color.Transparent
                        )
                    )
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "关闭", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            "${pagerState.currentPage + 1}/${list.size} · ${fmtSize(current.size)}" +
                                if (current.duration != null) " · ${fmtDuration(current.duration)}" else "",
                            color = Color(0xCCFFFFFF),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { vm.toggleFavorite(current) }) {
                        Icon(
                            if (current.fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (current.fav) DjiColors.DarkHeart else Color.White
                        )
                    }
                    IconButton(onClick = { showActions = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = Color.White)
                    }
                }
            }
        }

        playerError?.let { message ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "若为解码失败，可能是该设备不支持 HEVC 4K 视频；可尝试切换「流畅 LRF」",
                        color = Color(0xCCFFFFFF),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xCC000000)
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (current.type == MediaType.VIDEO) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fmtDuration(positionMs / 1000.0),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = (positionMs / 1000f).coerceIn(
                                0f,
                                (durationMs / 1000f).coerceAtLeast(0.1f)
                            ),
                            onValueChange = {
                                positionMs = (it * 1000).toLong()
                                player.seekTo(positionMs)
                            },
                            valueRange = 0f..(durationMs / 1000f).coerceAtLeast(0.1f),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            fmtDuration(durationMs / 1000.0),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                        }) {
                            Icon(Icons.Outlined.Replay10, contentDescription = "后退 10 秒", tint = Color.White)
                        }
                        IconButton(
                            onClick = { if (player.isPlaying) player.pause() else player.play() },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color(0x33FFFFFF), CircleShape)
                        ) {
                            Icon(
                                if (playing) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) {
                            Icon(Icons.Outlined.Forward10, contentDescription = "前进 10 秒", tint = Color.White)
                        }
                        IconButton(onClick = {
                            muted = !muted
                            player.volume = if (muted) 0f else 1f
                        }) {
                            Icon(
                                if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                contentDescription = "静音",
                                tint = Color.White
                            )
                        }
                        if (current.lrfPath != null) {
                            IconButton(onClick = {
                                quality = if (quality == "smooth") "hd" else "smooth"
                            }) {
                                Text(
                                    if (quality == "smooth") "LRF" else "HD",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showActions) {
        ItemActionSheet(
            item = current,
            onDismiss = { showActions = false },
            onFavorite = { vm.toggleFavorite(current); showActions = false },
            onMeta = { showMeta = true; showActions = false },
            onDownload = { vm.download(current); showActions = false },
            onTrash = {
                vm.moveToTrash(current)
                showActions = false
                onClose()
            },
            onSelect = { showActions = false }
        )
    }

    if (showMeta) {
        MetaSheet(vm, current.path, onDismiss = { showMeta = false })
    }
}

@Composable
private fun VideoPage(player: ExoPlayer, active: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = if (active) player else null },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PhotoPage(
    vm: AlbumViewModel,
    item: MediaItem,
    active: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, item.path) {
        value = runCatching { vm.loadFullPhoto(item.path) }.getOrNull()
    }

    var zoom by remember(item.path) { mutableStateOf(1f) }
    var offsetX by remember(item.path) { mutableStateOf(0f) }
    var offsetY by remember(item.path) { mutableStateOf(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(item.path) {
                // 自己处理手势：1x 时的单指拖动不消费，交给外层 Pager 翻页；
                // 双指或已放大时才消费，用于缩放与平移。
                fun bounds(z: Float): Offset {
                    val bmp = bitmap ?: return Offset(0f, 0f)
                    if (bmp.width <= 0 || bmp.height <= 0 || box.width <= 0 || box.height <= 0) {
                        return Offset(0f, 0f)
                    }
                    // 照片按 Fit 铺在屏幕里，真实显示尺寸要按短边缩放，再乘以缩放倍数
                    val fit = minOf(
                        box.width.toFloat() / bmp.width,
                        box.height.toFloat() / bmp.height
                    )
                    val shownW = bmp.width * fit * z
                    val shownH = bmp.height * fit * z
                    return Offset(
                        ((shownW - box.width) / 2f).coerceAtLeast(0f),
                        ((shownH - box.height) / 2f).coerceAtLeast(0f)
                    )
                }

                fun apply(newZoom: Float, panDelta: Offset) {
                    val z = newZoom.coerceIn(1f, 6f)
                    if (z <= 1.01f) {
                        zoom = 1f
                        offsetX = 0f
                        offsetY = 0f
                        onZoomChange(false)
                    } else {
                        val limit = bounds(z)
                        zoom = z
                        // 位移与手指 1:1 跟随（之前乘了 0.5，所以拖着走一半距离，像"不跟手"）
                        offsetX = (offsetX + panDelta.x).coerceIn(-limit.x, limit.x)
                        offsetY = (offsetY + panDelta.y).coerceIn(-limit.y, limit.y)
                        onZoomChange(true)
                    }
                }
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val prev = zoom
                            val next = (prev * event.calculateZoom()).coerceIn(1f, 6f)
                            // 双指缩放时以捏合中心为锚点，避免画面从手指下面跑掉
                            val k = next / prev
                            val focus = event.calculateCentroid(useCurrent = true) -
                                Offset(box.width / 2f, box.height / 2f)
                            val delta = event.calculatePan() +
                                (focus - (focus - Offset(offsetX, offsetY)) * k) -
                                Offset(offsetX, offsetY)
                            apply(next, delta)
                            event.changes.forEach { it.consume() }
                        } else {
                            val change = pressed.first()
                            if (zoom > 1.01f) {
                                apply(zoom, change.positionChange())
                                change.consume()
                            }
                            // 1x 单指：不消费 → Pager 横向滑动翻页
                        }
                    }
                }
            }
            .pointerInput(item.path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (zoom > 1.01f) {
                            zoom = 1f
                            offsetX = 0f
                            offsetY = 0f
                            onZoomChange(false)
                        } else {
                            zoom = 2.5f
                            onZoomChange(true)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }

        if (active && zoom > 1.01f) {
            Text(
                "${(zoom * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(Color(0x66000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
