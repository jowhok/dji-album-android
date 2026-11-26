package com.wei.djialbum.ui

import android.app.Application
import com.wei.djialbum.DjiAlbumApp
import com.wei.djialbum.model.AppInfo
import com.wei.djialbum.model.MediaItem
import com.wei.djialbum.model.NasConfig
import com.wei.djialbum.model.Progress
import com.wei.djialbum.model.PurgePreview
import com.wei.djialbum.model.PurgeResult
import com.wei.djialbum.model.ScanResult
import com.wei.djialbum.model.TrashItem
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ListState(
    val rows: List<MediaItem> = emptyList(),
    val tab: String = "all",
    val sort: String = "time",
    val favoritesOnly: Boolean = false,
    val date: String? = null,
    val loading: Boolean = true,
    val error: String? = null
) {
    val filtered: List<MediaItem>
        get() = if (date == null) rows else rows.filter { it.dirDate == date }
}

class AlbumViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = DjiAlbumApp.of(app)
    private val repo = appCtx.repo
    private val prefs = appCtx.prefs

    val themeMode = MutableStateFlow(prefs.themeMode)
    val configured = MutableStateFlow(prefs.configured)
    val config = MutableStateFlow(prefs.config())
    val nasOk = MutableStateFlow<Boolean?>(null)
    val nasError = MutableStateFlow("")
    val toast = MutableStateFlow<String?>(null)
    val banner = MutableStateFlow<String?>(null)

    val scanProgress: StateFlow<Progress?> = repo.scanProgress
    val thumbProgress: StateFlow<Progress?> = repo.thumbProgress
    val metaProgress: StateFlow<Progress?> = repo.metaProgress

    val timeline = MutableStateFlow(ListState())
    val favorites = MutableStateFlow(ListState(favoritesOnly = true))
    val trash = MutableStateFlow<List<TrashItem>>(emptyList())
    val info = MutableStateFlow<AppInfo?>(null)
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val downloadProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val scanning = MutableStateFlow(false)
    val autoThumbStarted = MutableStateFlow(false)

    val nasThumbsOn = MutableStateFlow(prefs.nasThumbs)
    val nasThumbStats = MutableStateFlow<Triple<Int, Boolean?, Boolean>>(Triple(0, null, false))

    init {
        viewModelScope.launch {
            if (prefs.configured) {
                refreshTimeline(scan = true)
                refreshFavorites()
                checkNas()
                // 本地缩略图已就绪时，顺手和 NAS 上的小图对一次账（补传/清孤儿）
                launch { loadNasThumbStats() }
            }
            loadInfo()
        }
    }

    fun setTheme(mode: String) {
        prefs.themeMode = mode
        themeMode.value = mode
    }

    // ---------------- NAS 缩略图镜像 ----------------

    fun setNasThumbs(on: Boolean) {
        prefs.nasThumbs = on
        nasThumbsOn.value = on
        repo.resetNasThumbCache()
        if (on) {
            loadNasThumbStats()
            // 打开开关时顺手把已生成的缩略图补传上去
            startThumbs(notify = false)
        }
    }

    fun loadNasThumbStats() {
        viewModelScope.launch {
            nasThumbStats.value = repo.syncNasMirror()
        }
    }

    fun clearNasThumbs() {
        viewModelScope.launch {
            val n = repo.clearNasThumbs()
            showToast("已删除 NAS 上的 $n 张缩略图")
            loadNasThumbStats()
        }
    }

    fun showToast(text: String) {
        toast.value = text
    }

    fun clearToast() {
        toast.value = null
    }

    fun checkNas() {
        viewModelScope.launch {
            nasOk.value = null
            nasOk.value = repo.status()
            nasError.value = if (nasOk.value == false) "NAS 不可达，请检查网络与设置" else ""
        }
    }

    fun setTab(tab: String, favoriteScope: Boolean) {
        // rows 里始终是全部媒体，tab 只在本地过滤，切换时不必重新查库，
        // 这样左右滑动切页是即时的，不会闪一下 loading。
        val state = if (favoriteScope) favorites else timeline
        if (state.value.tab == tab) return
        state.value = state.value.copy(tab = tab)
    }

    fun setSort(sort: String, favoriteScope: Boolean) {
        val state = if (favoriteScope) favorites else timeline
        state.value = state.value.copy(sort = sort, loading = true)
        viewModelScope.launch { loadRows(favoriteScope) }
    }

    fun setDate(date: String?, favoriteScope: Boolean) {
        val state = if (favoriteScope) favorites else timeline
        state.value = state.value.copy(date = date)
    }

    fun refreshTimeline(scan: Boolean) {
        viewModelScope.launch {
            if (scan) runScan(silent = true)
            loadRows(false)
            loadRows(true)
            maybeAutoThumbs()
        }
    }

    fun refreshFavorites() {
        viewModelScope.launch { loadRows(true) }
    }

    private suspend fun loadRows(favoriteScope: Boolean) {
        val state = if (favoriteScope) favorites else timeline
        val s = state.value
        runCatching {
            // 一次取回全部（含视频+照片），界面层再按 tab 过滤，切页无需再查库
            val rows = repo.listMedia("all", s.sort == "size", s.favoritesOnly)
            state.value = s.copy(rows = rows, loading = false, error = null)
        }.onFailure {
            state.value = s.copy(loading = false, error = it.message ?: "索引读取失败")
        }
    }

    private suspend fun runScan(silent: Boolean = false): ScanResult? {
        if (scanning.value) return null
        scanning.value = true
        return try {
            val r = repo.scan()
            if (!silent) showToast("扫描完成：${r.videos} 个视频 / ${r.photos} 张照片")
            nasOk.value = true
            r
        } catch (e: Exception) {
            if (!silent) banner.value = "扫描失败：${e.message}"
            nasOk.value = false
            nasError.value = e.message ?: "未知错误"
            null
        } finally {
            scanning.value = false
        }
    }

    fun rescan(showResult: Boolean = true) {
        viewModelScope.launch {
            runScan(silent = !showResult)
            loadRows(false)
            loadRows(true)
            loadInfo()
            autoThumbStarted.value = false
            maybeAutoThumbs()
        }
    }

    private var thumbJob: kotlinx.coroutines.Job? = null

    private fun startThumbs(notify: Boolean) {
        if (thumbJob?.isActive == true) return
        thumbJob = viewModelScope.launch {
            var lastReload = 0
            // 每做完一小批就把 rows 重新读一遍，格子边生成边显示，不用等全部跑完
            val p = repo.generateThumbs { progress ->
                if (progress.done - lastReload >= 12 || progress.done == progress.total) {
                    lastReload = progress.done
                    viewModelScope.launch {
                        loadRows(false)
                        loadRows(true)
                    }
                }
            }
            if (p.total > 0) {
                loadRows(false)
                loadRows(true)
                loadInfo()
                if (notify) showToast("缩略图完成 ${p.done}/${p.total}，失败 ${p.failed}")
            }
        }
    }

    private fun maybeAutoThumbs() {
        val rows = timeline.value.rows
        if (rows.isEmpty()) return
        if (rows.none { it.thumb.isNullOrEmpty() }) return
        startThumbs(notify = false)
    }

    fun generateThumbs() = startThumbs(notify = true)

    fun generateMeta() {
        viewModelScope.launch {
            val p = repo.generateMetadata()
            showToast("元数据完成 ${p.done}/${p.total}，失败 ${p.failed}")
            loadRows(false)
            loadInfo()
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            val now = repo.toggleFavorite(item.path)
            showToast(if (now) "已加入收藏" else "已取消收藏")
            loadRows(false)
            loadRows(true)
            loadInfo()
        }
    }

    fun setFavoriteSelection(fav: Boolean) {
        val paths = selection.value
        if (paths.isEmpty()) return
        viewModelScope.launch {
            repo.setFavorite(paths, fav)
            showToast(if (fav) "已收藏 ${paths.size} 项" else "已取消收藏 ${paths.size} 项")
            selection.value = emptySet()
            loadRows(false)
            loadRows(true)
            loadInfo()
        }
    }

    fun moveSelectionToTrash() {
        val paths = selection.value
        if (paths.isEmpty()) return
        viewModelScope.launch {
            repo.moveToTrash(paths)
            showToast("已移入回收站 ${paths.size} 项")
            selection.value = emptySet()
            loadRows(false)
            loadRows(true)
            loadTrash()
            loadInfo()
        }
    }

    fun moveToTrash(item: MediaItem) {
        viewModelScope.launch {
            repo.moveToTrash(listOf(item.path))
            showToast("已移入回收站")
            loadRows(false)
            loadRows(true)
            loadTrash()
            loadInfo()
        }
    }

    fun loadTrash() {
        viewModelScope.launch { trash.value = repo.listTrash() }
    }

    fun restore(paths: Collection<String>) {
        viewModelScope.launch {
            repo.restore(paths)
            showToast("已恢复 ${paths.size} 项")
            loadTrash()
            loadRows(false)
            loadRows(true)
            loadInfo()
        }
    }

    suspend fun purgePreview(paths: Collection<String>): PurgePreview = withContext(Dispatchers.IO) {
        repo.purgePreview(paths)
    }

    fun purge(paths: Collection<String>, onDone: (PurgeResult) -> Unit) {
        viewModelScope.launch {
            val r = repo.purge(paths)
            showToast(
                if (r.failed == 0) "已彻底删除 ${r.succeeded} 项，释放 ${com.wei.djialbum.core.fmtSize(r.bytes)}"
                else "删除完成 ${r.succeeded} 项，失败 ${r.failed} 项"
            )
            loadTrash()
            loadInfo()
            onDone(r)
        }
    }

    suspend fun readMeta(path: String) = withContext(Dispatchers.IO) { repo.readMeta(path) }

    fun download(item: MediaItem) {
        viewModelScope.launch {
            downloadProgress.value = 0L to item.size
            runCatching {
                repo.download(item) { done, total ->
                    downloadProgress.value = done to total
                }
            }.onSuccess { where ->
                showToast("已保存到 $where")
            }.onFailure {
                banner.value = "下载失败：${it.message}"
            }
            downloadProgress.value = null
        }
    }

    suspend fun testConnection(cfg: NasConfig): Result<Unit> = repo.testConnection(cfg)

    suspend fun saveAndScan(cfg: NasConfig): Result<ScanResult> = withContext(Dispatchers.IO) {
        runCatching {
            repo.saveConfig(cfg).getOrThrow()
            config.value = prefs.config()
            nasOk.value = true
            val r = repo.scan()
            // 首次配置时 ViewModel 的 init 还跳过刷新（configured 仍是 false），
            // 这里必须自己把列表读进来，否则进时光轴会一直转圈。
            loadRows(false)
            loadRows(true)
            configured.value = true
            maybeAutoThumbs()
            r
        }
    }

    /** 进主界面前的兜底：列表没加载过就补一次 */
    fun ensureLoaded() {
        viewModelScope.launch {
            if (timeline.value.loading || timeline.value.rows.isEmpty()) {
                loadRows(false)
                loadRows(true)
            }
            maybeAutoThumbs()
        }
    }

    fun saveConfigOnly(cfg: NasConfig, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val r = repo.saveConfig(cfg)
            if (r.isSuccess) {
                config.value = prefs.config()
                nasOk.value = true
                loadInfo()
            }
            onDone(r)
        }
    }

    fun loadInfo() {
        viewModelScope.launch { info.value = withContext(Dispatchers.IO) { repo.appInfo() } }
    }

    fun clearCache() {
        viewModelScope.launch {
            val (files, bytes) = withContext(Dispatchers.IO) { repo.clearCache() }
            showToast("已清理 $files 个缓存文件，释放 ${com.wei.djialbum.core.fmtSize(bytes)}")
            loadRows(false)
            loadRows(true)
            loadInfo()
        }
    }

    fun trimCache() {
        viewModelScope.launch {
            val (files, bytes) = withContext(Dispatchers.IO) { repo.trimCache() }
            showToast(
                if (files > 0) "已淘汰 $files 个最旧缩略图，释放 ${com.wei.djialbum.core.fmtSize(bytes)}"
                else "缓存未超限，无需清理"
            )
            loadRows(false)
            loadInfo()
        }
    }

    fun toggleSelection(path: String) {
        val cur = selection.value
        selection.value = if (path in cur) cur - path else cur + path
    }

    fun selectAll(paths: Collection<String>) {
        selection.value = if (selection.value.containsAll(paths)) emptySet() else paths.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun thumbFile(name: String?): java.io.File? =
        if (name.isNullOrEmpty()) null else appCtx.thumbs.file(name).takeIf { it.isFile }

    /** 播放器用：分页加载全尺寸（最大 2560px，避免 8K 原片 OOM） */
    suspend fun loadFullPhoto(path: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) { repo.loadPhoto(path, 2560) }

    val smb get() = repo.fs

    companion object {
        const val PLAYER_SCHEME = "dji-smb"
    }
}