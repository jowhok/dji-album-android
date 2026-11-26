package com.wei.djialbum.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.wei.djialbum.core.fmtSize
import com.wei.djialbum.model.AppInfo
import com.wei.djialbum.model.MediaItem
import com.wei.djialbum.model.MediaMeta
import com.wei.djialbum.model.MediaType
import com.wei.djialbum.model.NasConfig
import com.wei.djialbum.model.Progress
import com.wei.djialbum.model.PurgeFile
import com.wei.djialbum.model.PurgePlanItem
import com.wei.djialbum.model.PurgePreview
import com.wei.djialbum.model.PurgeResult
import com.wei.djialbum.model.ScanResult
import com.wei.djialbum.model.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val TAG = "MediaRepository"
private val DATE_DIR = Regex("^\\d{8}$")
private val PHOTO_EXT = setOf("jpg", "jpeg")
private val ALLOWED_EXT = setOf("mp4", "lrf", "jpg", "jpeg")
private const val THUMB_WIDTH = 360

class MediaRepository(
    private val prefs: Prefs,
    private val db: AlbumDb,
    private val thumbs: ThumbStore,
    private val appContext: Context
) {

    val fs = SmbFs { prefs.config() }

    private val _scanProgress = MutableStateFlow<Progress?>(null)
    val scanProgress: StateFlow<Progress?> = _scanProgress

    private val _thumbProgress = MutableStateFlow<Progress?>(null)
    val thumbProgress: StateFlow<Progress?> = _thumbProgress

    private val _metaProgress = MutableStateFlow<Progress?>(null)
    val metaProgress: StateFlow<Progress?> = _metaProgress

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    // ---------------- 连接 ----------------

    suspend fun testConnection(cfg: NasConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { fs.testConnection(cfg) }
    }

    suspend fun saveConfig(cfg: NasConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            fs.testConnection(cfg)
            prefs.saveConfig(cfg)
            fs.invalidate()
        }
    }

    suspend fun status(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = prefs.config()
            if (!cfg.isValid()) return@runCatching false
            fs.share()
            true
        }.getOrDefault(false)
    }

    // ---------------- 扫描 ----------------

    suspend fun scan(onProgress: ((Progress) -> Unit)? = null): ScanResult = withContext(Dispatchers.IO) {
        val cfg = prefs.config()
        require(cfg.isValid()) { "请先在设置页配置 NAS" }
        val root = cfg.rootDir
        android.util.Log.i("DJI-SCAN", "scan start root='$root' host='${cfg.host}'")
        val rootEntries = fs.list(root).filter { it.isDirectory && DATE_DIR.matches(it.name) }
        android.util.Log.i("DJI-SCAN", "root dirs=${rootEntries.size}")
        val seen = mutableSetOf<String>()
        var videos = 0
        var photos = 0
        val total = rootEntries.size

        rootEntries.forEachIndexed { index, entry ->
            val dirName = entry.name
            val dirRel = joinPath(root, dirName)
            val names = runCatching { fs.list(dirRel) }.getOrElse {
                android.util.Log.w("DJI-SCAN", "list $dirRel failed: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }
            android.util.Log.i("DJI-SCAN", "dir $dirName entries=${names.size} (${index + 1}/$total)")
            val mp4s = mutableMapOf<String, SmbEntry>()
            val lrfs = mutableMapOf<String, SmbEntry>()

            db.inTransaction {
                names.filter { !it.isDirectory }.forEach { f ->
                    val dot = f.name.lastIndexOf('.')
                    if (dot <= 0) return@forEach
                    val ext = f.name.substring(dot + 1).lowercase()
                    val base = f.name.substring(0, dot)
                    when {
                        ext == "mp4" -> mp4s[base] = f
                        ext == "lrf" -> lrfs[base] = f
                        ext in PHOTO_EXT -> {
                            val rel = joinPath(dirRel, f.name)
                            db.upsertFile(rel, f.name, MediaType.PHOTO, dirName, f.size, f.modifiedAt, null)
                            seen += rel
                            photos++
                        }
                    }
                }

                (mp4s.keys + lrfs.keys).forEach { base ->
                    val mp4 = mp4s[base]
                    val lrf = lrfs[base]
                    val primary = mp4 ?: lrf ?: return@forEach
                    val name = base + if (mp4 != null) ".mp4" else ".lrf"
                    val rel = joinPath(dirRel, primary.name)
                    db.upsertFile(
                        rel, name, MediaType.VIDEO, dirName, primary.size, primary.modifiedAt,
                        lrf?.let { joinPath(dirRel, it.name) }
                    )
                    seen += rel
                    videos++
                }
            }

            _scanProgress.value = Progress(index + 1, total)
            onProgress?.invoke(Progress(index + 1, total))
        }

        db.deleteFilesNotIn(seen)
        _scanProgress.value = null
        android.util.Log.i("DJI-SCAN", "scan done videos=$videos photos=$photos dirs=$total")
        ScanResult(videos = videos, photos = photos, dirs = total)
    }

    // ---------------- 列表 / 收藏 ----------------

    suspend fun listMedia(type: String, sortBySize: Boolean, favoritesOnly: Boolean): List<MediaItem> =
        withContext(Dispatchers.IO) { db.listMedia(type, sortBySize, favoritesOnly) }

    suspend fun toggleFavorite(path: String): Boolean = withContext(Dispatchers.IO) {
        db.toggleFavorite(path)
    }

    suspend fun setFavorite(paths: Collection<String>, fav: Boolean) = withContext(Dispatchers.IO) {
        db.setFavorite(paths, fav)
    }

    suspend fun favoritePaths(): Set<String> = withContext(Dispatchers.IO) { db.allFavoritePaths() }

    // ---------------- 缩略图 ----------------

    private val thumbGate = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun generateThumbs(onProgress: ((Progress) -> Unit)? = null): Progress =
        withContext(Dispatchers.IO) {
            if (!thumbGate.compareAndSet(false, true)) {
                android.util.Log.i("DJI-THUMB", "already running, skip")
                return@withContext Progress(0, 0)
            }
            try {
                generateThumbsLocked(onProgress)
            } finally {
                thumbGate.set(false)
            }
        }

    private val bytesRead = java.util.concurrent.atomic.AtomicLong(0)

    /** NAS 侧缩略图镜像（开关在设置页，默认开） */
    val nasThumbs = NasThumbMirror(fs) { prefs.rootDir }

    private fun nasMirrorOn(): Boolean = prefs.nasThumbs

    suspend fun nasThumbStats(): Triple<Int, Boolean?, Boolean> = withContext(Dispatchers.IO) {
        syncNasMirror()
    }

    private fun keepFingerprints(): Set<String> = db.thumbFingerprintSources()
        .map { ThumbStore.sha1("${it.first}|${it.second}|${it.third}") + ".jpg" }
        .toSet()

    /**
     * 与 NAS 的小图做一次对账：拉清单 → 补传本地已有但远端缺的 → 删掉没有原片的孤儿。
     * 缩略图本身已全部就绪（pending=0）时也会跑这个，保证首次开启镜像能补齐历史。
     */
    suspend fun syncNasMirror(): Triple<Int, Boolean?, Boolean> = withContext(Dispatchers.IO) {
        if (!prefs.nasThumbs) return@withContext Triple(0, null, false)
        runCatching {
            nasThumbs.reset()
            val ok = nasThumbs.ensureListed()
            if (ok) {
                backfillNasThumbs()
                val keep = keepFingerprints()
                if (keep.isNotEmpty()) nasThumbs.pruneOrphans(keep)
            }
            android.util.Log.i(
                "DJI-THUMB",
                "nas mirror sync writable=${nasThumbs.writable} remote=${nasThumbs.remoteCount()}"
            )
            Triple(nasThumbs.remoteCount(), nasThumbs.writable, true)
        }.getOrElse {
            android.util.Log.w("DJI-THUMB", "nas mirror sync failed: ${it.message}")
            Triple(0, null, false)
        }
    }

    suspend fun clearNasThumbs(): Int = withContext(Dispatchers.IO) {
        runCatching { nasThumbs.clearAll() }.getOrDefault(0)
    }

    fun resetNasThumbCache() = nasThumbs.reset()

    private fun generateThumbsLocked(onProgress: ((Progress) -> Unit)?): Progress {
            val pending = db.itemsPendingThumb().filter { !thumbs.isValid(it.thumb) }
            // 只做本地已有缓存校验后仍缺的那些；顺序按日期倒序，先出屏最上面几屏
            android.util.Log.i("DJI-THUMB", "gate pass, pending=${pending.size}")
            val total = pending.size
            val state = Progress(0, total)
            var done = 0
            var failed = 0
            _thumbProgress.value = state.copy()
            val t0 = System.currentTimeMillis()

            // 先把 NAS 上的小图清单拉一次（一次 list，几百条），之后命中就只下几十 KB
            var mirrorOn = false
            if (total > 0 && nasMirrorOn()) {
                mirrorOn = runCatching { nasThumbs.ensureListed() }.getOrDefault(false)
                android.util.Log.i(
                    "DJI-THUMB",
                    "nas mirror ready=$mirrorOn writable=${nasThumbs.writable} remote=${nasThumbs.remoteCount()}"
                )
            }

            // 把待办切片分给各个 worker，每条只处理一次
            // （之前 3 个线程各自遍历整个列表，同一张图会被 3 个线程同时去 SMB 抢读，等于 3 倍浪费）
            val workers = 6
            val threads = (0 until workers).map { index ->
                Thread {
                    var i = index
                    while (i < total) {
                        val item = pending[i]
                        try {
                            val name = thumbs.nameFor(item.path)
                            if (thumbFor(item, name, mirrorOn)) db.updateThumb(item.path, name)
                            else { synchronized(state) { failed++ }; db.bumpThumbTry(item.path) }
                        } catch (e: Exception) {
                            Log.w(TAG, "thumb fail ${item.name}: ${e.message}")
                            synchronized(state) { failed++ }
                            db.bumpThumbTry(item.path)
                        } finally {
                            val p = synchronized(state) {
                                done++
                                Progress(done, total, failed).also { _thumbProgress.value = it }
                            }
                            onProgress?.invoke(p)
                        }
                        i += workers
                    }
                }.apply { start() }
            }
            threads.forEach { it.join() }
            thumbs.trim()
            // 收尾一：把本地已有、NAS 还缺的小图补齐（首次开启镜像时会有一批，每张几十 KB）
            if (mirrorOn && nasThumbs.writable == true) {
                runCatching { backfillNasThumbs() }
            }
            // 收尾二：删掉 NAS 上已经没有对应原片的旧小图（用扫描后的索引做保留集）
            if (mirrorOn && done > 0) {
                runCatching {
                    val pruned = nasThumbs.pruneOrphans(keepFingerprints())
                    if (pruned > 0) android.util.Log.i("DJI-THUMB", "nas mirror pruned=$pruned")
                }
            }
            _thumbProgress.value = null
            android.util.Log.i(
                "DJI-THUMB",
                "done=$done total=$total failed=$failed in ${(System.currentTimeMillis() - t0)}ms readMB=${bytesRead.get() / 1024 / 1024}"
            )
            return Progress(done, total, failed)
        }

    /** 本地已有、NAS 缺的小图补传（每张约 40KB，串行跑，失败下次再说） */
    private fun backfillNasThumbs() {
        var pushed = 0
        var tried = 0
        var failed = 0
        val src = db.thumbFingerprintSources()
        src.forEach { (path, size, taken) ->
            val local = thumbs.nameFor(path)
            if (!thumbs.isValid(local)) return@forEach
            tried++
            val remote = ThumbStore.sha1("$path|$size|$taken") + ".jpg"
            if (nasThumbs.has(remote)) return@forEach
            if (nasThumbs.push(local, remote, thumbs)) pushed++ else failed++
        }
        android.util.Log.i(
            "DJI-THUMB",
            "nas mirror backfill rows=${src.size} localValid=$tried pushed=$pushed failed=$failed"
        )
    }

    private fun thumbFor(item: MediaItem, name: String, mirrorOn: Boolean): Boolean {
        val out = thumbs.file(name)
        if (thumbs.isValid(name)) return true
        // NAS 上有别人/上次生成过的小图：只下几十 KB，别去啃 12~22MB 的原片
        if (mirrorOn) {
            val remote = nasThumbs.nameFor(item)
            if (nasThumbs.has(remote) && nasThumbs.fetch(remote, name, thumbs)) return true
        }
        return when (item.type) {
            MediaType.PHOTO -> photoThumb(item.path)?.let { bmp ->
                thumbs.save(name, bmp).also {
                    bmp.recycle()
                    if (it && mirrorOn) nasThumbs.push(name, nasThumbs.nameFor(item), thumbs)
                }
            } ?: false
            MediaType.VIDEO -> videoThumb(item).let { bmp ->
                if (bmp == null) false else thumbs.save(name, bmp).also {
                    bmp.recycle()
                    if (mirrorOn) nasThumbs.push(name, nasThumbs.nameFor(item), thumbs)
                }
            }
        }.also { if (!it) runCatching { out.delete() } }
    }

    private fun photoThumb(path: String): Bitmap? {
        // 只下载一次原图字节：之前 bounds 解码、正式解码、EXIF 各开一次流，
        // 一张 8MB 的照片要往 SMB 跑三趟（≈3 倍流量和往返）
        val bytes = runCatching { readAllBytes(path) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        bytesRead.addAndGet(bytes.size.toLong())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scaled = scaleToWidth(bmp, THUMB_WIDTH)
        val orientation = runCatching {
            ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        return rotateByDegrees(scaled, orientation)
    }

    /**
     * 一次性把 SMB 文件读进内存（照片缩略图用，几十 MB 以内可接受）。
     * 实测：单文件多流分段读并没有比单流快（瓶颈在 NAS 侧总吞吐），这里保持单流读，省内存省复杂度。
     */
    private fun readAllBytes(relative: String): ByteArray {
        val size = fs.size(relative)
        if (size <= 0 || size > 64L * 1024 * 1024) return ByteArray(0)
        val buf = ByteArray(size.toInt())
        val reader = fs.openReader(relative)
        try {
            var off = 0
            while (off < buf.size) {
                val n = reader.read(off.toLong(), buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            return if (off == buf.size) buf else buf.copyOf(off)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun videoThumb(item: MediaItem): Bitmap? {
        // LRF 代理优先（体积小），失败再回退到原片 MP4
        val sources = listOfNotNull(item.lrfPath, item.path).distinct()
        for (source in sources) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(SmbSource(fs, source).mediaDataSource())
                val frame = runCatching {
                    mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: runCatching {
                    mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (frame != null) return scaleToWidth(frame, THUMB_WIDTH)
            } catch (e: Exception) {
                Log.w(TAG, "video thumb fail $source: ${e.message}")
            } finally {
                runCatching { mmr.release() }
            }
        }
        return null
    }

    private fun scaleToWidth(src: Bitmap, width: Int): Bitmap {
        if (src.width <= width) return src
        val h = (src.height * (width.toFloat() / src.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, width, h, true)
    }

    private fun rotateByExif(path: String, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            openInput(path).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrDefault(1)
        return rotateByDegrees(bmp, orientation)
    }

    private fun rotateByDegrees(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    // ---------------- 元数据 ----------------

    suspend fun generateMetadata(onProgress: ((Progress) -> Unit)? = null): Progress =
        withContext(Dispatchers.IO) {
            val pending = db.itemsPendingMeta()
            var done = 0
            var failed = 0
            _metaProgress.value = Progress(0, pending.size)
            pending.forEach { item ->
                try {
                    probe(item)
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "meta fail ${item.name}: ${e.message}")
                }
                done++
                val p = Progress(done, pending.size, failed)
                _metaProgress.value = p
                onProgress?.invoke(p)
            }
            _metaProgress.value = null
            Progress(done, pending.size, failed)
        }

    private fun probe(item: MediaItem) {
        when (item.type) {
            MediaType.PHOTO -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openInput(item.path).use { BitmapFactory.decodeStream(it, null, bounds) }
                db.updateMeta(item.path, null, bounds.outWidth.takeIf { it > 0 }, bounds.outHeight.takeIf { it > 0 }, null, null)
            }
            MediaType.VIDEO -> {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(SmbSource(fs, item.lrfPath ?: item.path).mediaDataSource())
                    val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble()?.div(1000.0)
                    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    db.updateMeta(item.path, duration, w, h, bitrate, mime)
                } finally {
                    runCatching { mmr.release() }
                }
            }
        }
    }

    suspend fun readMeta(path: String): MediaMeta? = withContext(Dispatchers.IO) {
        val item = db.trashEntryFor(path) ?: db.listMedia("all", false, false).firstOrNull { it.path == path }
        if (item == null) return@withContext null
        if (item.metaAt == 0L) runCatching { probe(item) }
        val lrfSize = item.lrfPath?.let { runCatching { fs.size(it) }.getOrNull() }
        val exists = runCatching { fs.exists(item.path) }.getOrDefault(false)
        val fresh = db.listMedia("all", false, false).firstOrNull { it.path == path } ?: item
        val extra = db.metaOf(path)
        MediaMeta(
            path = item.path,
            name = item.name,
            type = item.type,
            dirDate = item.dirDate,
            size = item.size,
            takenAt = item.takenAt,
            lrfPath = item.lrfPath,
            lrfSize = lrfSize,
            duration = extra.duration,
            width = extra.width,
            height = extra.height,
            bitrate = extra.bitrate,
            vcodec = extra.vcodec,
            exists = exists
        )
    }

    // ---------------- 回收站 / 彻底删除 ----------------

    suspend fun moveToTrash(paths: Collection<String>) = withContext(Dispatchers.IO) {
        db.moveToTrash(paths)
    }

    suspend fun listTrash(): List<TrashItem> = withContext(Dispatchers.IO) { db.listTrash() }

    suspend fun restore(paths: Collection<String>) = withContext(Dispatchers.IO) {
        db.restoreFromTrash(paths)
    }

    suspend fun purgePreview(paths: Collection<String>): PurgePreview = withContext(Dispatchers.IO) {
        val root = prefs.rootDir
        val items = paths.toSet().mapNotNull { p ->
            val item = db.trashEntryFor(p) ?: return@mapNotNull null
            if (!isSafe(item.path, root)) return@mapNotNull null
            val candidates = (listOf(item.path) + listOfNotNull(item.lrfPath)).distinct()
                .filter { isSafe(it, root) }
            if (candidates.isEmpty()) return@mapNotNull null
            val files = candidates.map { rel ->
                val size = runCatching { fs.size(rel) }.getOrDefault(0L)
                PurgeFile(rel, size, size > 0)
            }
            PurgePlanItem(item.path, item.name, item.type, item.size, item.thumb, files)
        }
        PurgePreview(
            items = items,
            totalFiles = items.sumOf { it.files.size },
            totalBytes = items.sumOf { i -> i.files.sumOf { if (it.exists) it.size else 0L } }
        )
    }

    suspend fun purge(paths: Collection<String>): PurgeResult = withContext(Dispatchers.IO) {
        val preview = purgePreview(paths)
        var succeeded = 0
        var failed = 0
        var bytes = 0L
        val errors = mutableListOf<String>()
        preview.items.forEach { plan ->
            var ok = true
            plan.files.forEach { f ->
                if (!f.exists) return@forEach
                val deleted = fs.delete(f.path)
                if (deleted) bytes += f.size else {
                    ok = false
                    errors += "${f.path} 删除失败"
                }
            }
            if (ok) {
                db.purgeRecords(listOf(plan.path))
                thumbs.delete(plan.thumb)
                succeeded++
            } else {
                failed++
            }
        }
        PurgeResult(preview.items.size, succeeded, failed, bytes, errors)
    }

    private fun isSafe(relative: String, root: String): Boolean {
        if (relative.isBlank() || relative.contains("..")) return false
        val ext = relative.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXT) return false
        val normalizedRoot = root.trim('/').replace('\\', '/')
        if (normalizedRoot.isEmpty()) return true
        return relative == normalizedRoot || relative.startsWith("$normalizedRoot/")
    }

    // ---------------- 缓存 ----------------

    fun appInfo(): AppInfo {
        val (files, bytes) = thumbs.stats()
        return db.stats(files, bytes, thumbs.limitBytes, prefs.config().let { "${it.host}/${it.share}/${it.rootDir}" })
    }

    fun clearCache(): Pair<Int, Long> {
        val r = thumbs.clear()
        db.clearThumbRefs()
        return r
    }

    fun trimCache(): Pair<Int, Long> {
        val r = thumbs.trim()
        if (r.first > 0) db.clearThumbRefs()
        return r
    }

    // ---------------- 打开媒体 ----------------

    suspend fun loadPhoto(path: String, maxDim: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openInput(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            // 目标：解出的长边不超过 maxDim。用 sample 直接除，避免 8K 原片仍按 4096 宽解码（≈50MB）
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = openInput(path).use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
            rotateByExif(path, bmp)
        }.getOrNull()
    }

    fun openInput(relative: String): InputStream =
        BufferedInputStream(fs.inputStream(relative), 64 * 1024)

    suspend fun download(item: MediaItem, onProgress: (Long, Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            val total = runCatching { fs.size(item.path) }.getOrElse { item.size }
            var written = 0L
            val mime = if (item.type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection =
                    if (item.type == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (item.type == MediaType.VIDEO) "Movies/DJI Album" else "Pictures/DJI Album")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(collection, values) ?: throw IOException("无法创建媒体库记录")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        openInput(item.path).use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                onProgress(written, total)
                            }
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "媒体库 / DJI Album"
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (item.type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ),
                    "DJI Album"
                ).apply { mkdirs() }
                val out = File(dir, item.name)
                out.outputStream().use { os ->
                    openInput(item.path).use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
                out.absolutePath
            }
        }

    fun sizeOf(relative: String): Long = runCatching { fs.size(relative) }.getOrDefault(0L)

    companion object {
        fun joinPath(vararg parts: String): String =
            parts.filter { it.isNotBlank() }.joinToString("/") { it.trim('/').replace('\\', '/') }
    }
}

/** 把 SMB 文件包装成 MediaDataSource，供 MediaMetadataRetriever 使用 */
class SmbSource(private val fs: SmbFs, private val relative: String) {

    fun mediaDataSource(): MediaDataSource {
        val size = fs.size(relative)
        val reader = SmbByteReader(fs.openFile(relative), size)
        return object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= reader.size) return -1
                val n = reader.read(position, buffer, offset, size)
                return if (n <= 0) -1 else n
            }

            override fun getSize(): Long = reader.size

            override fun close() {
                reader.close()
            }
        }
    }
}
