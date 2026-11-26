package com.wei.djialbum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.wei.djialbum.ui.player.PlayerScreen
import com.wei.djialbum.ui.settings.SettingsScreen
import com.wei.djialbum.ui.setup.SetupScreen
import com.wei.djialbum.ui.timeline.TimelineScreen
import com.wei.djialbum.ui.theme.isDark
import com.wei.djialbum.ui.trash.TrashScreen

private data class Tab(val label: String, val icon: ImageVector)

// 底部只留三个入口，设置挪到时光轴顶部的功能按钮组
private val TABS = listOf(
    Tab("时光轴", Icons.Outlined.PhotoLibrary),
    Tab("收藏", Icons.Outlined.FavoriteBorder),
    Tab("回收站", Icons.Outlined.DeleteOutline)
)

@Composable
fun AppRoot(vm: AlbumViewModel) {
    val configured by vm.configured.collectAsState()
    val mode by vm.themeMode.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val dark = isDark(mode)

    // 状态栏/导航栏图标与主题保持一致
    SideEffect {
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    // 首次配置完成 / 冷启动进入主界面时，确保索引列表已经读到内存
    LaunchedEffect(configured) {
        if (configured) vm.ensureLoaded()
    }

    if (!configured) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            SetupScreen(vm, Modifier.padding(padding))
        }
        return
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    var playerPath by rememberSaveable { mutableStateOf<String?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val timelineRows by vm.timeline.collectAsState()
    val favoriteRows by vm.favorites.collectAsState()

    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    TABS.forEachIndexed { index, t ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = {
                                if (tab != index) {
                                    tab = index
                                    when (index) {
                                        1 -> vm.refreshFavorites()
                                        2 -> vm.loadTrash()
                                    }
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                // 页面切换动画：新页从右滑入、旧页向左淡出
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (slideInHorizontally(
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            initialOffsetX = { it / 3 }
                        ) + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(260, easing = FastOutSlowInEasing),
                                targetOffsetX = { -it / 3 }
                            ) + fadeOut(tween(160)))
                    },
                    label = "tab",
                    modifier = Modifier.fillMaxSize()
                ) { t ->
                    when (t) {
                        0 -> TimelineScreen(
                            vm = vm,
                            favoritesOnly = false,
                            modifier = Modifier.fillMaxSize(),
                            bottomPadding = padding.calculateBottomPadding(),
                            onOpen = {
                                playerPath = it
                                playerVisible = true
                            },
                            onOpenSettings = { settingsOpen = true }
                        )
                        1 -> TimelineScreen(
                            vm = vm,
                            favoritesOnly = true,
                            modifier = Modifier.fillMaxSize(),
                            bottomPadding = padding.calculateBottomPadding(),
                            onOpen = {
                                playerPath = it
                                playerVisible = true
                            },
                            onOpenSettings = { settingsOpen = true }
                        )
                        else -> TrashScreen(vm, Modifier.fillMaxSize(), padding.calculateBottomPadding())
                    }
                }
            }
        }

        // 设置页：从右侧滑入，返回时滑出
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160)),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize()
            ) {
                SettingsScreen(
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                    bottomPadding = 0.dp,
                    onBack = { settingsOpen = false }
                )
            }
        }

        // 预览：从下往上滑入进入，关闭时下滑退出
        val rows = if (tab == 1) favoriteRows.rows else timelineRows.rows
        playerPath?.let { path ->
            AnimatedVisibility(
                visible = playerVisible,
                enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200))
            ) {
                PlayerScreen(
                    vm = vm,
                    rows = rows,
                    startPath = path,
                    onClose = {
                        playerVisible = false
                        vm.refreshTimeline(scan = false)
                        vm.refreshFavorites()
                    }
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}
