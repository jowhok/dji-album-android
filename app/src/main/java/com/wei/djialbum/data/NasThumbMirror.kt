package com.wei.djialbum.data

import com.wei.djialbum.model.MediaItem
import java.io.File

/**
 * NAS 侧缩略图镜像：<根目录>/.dji-album/thumbs/<指纹>.jpg
 *
 * 目的：8K 原片单张 12~22MB，299 张照片重建一次缩略图要下 4.7GB；
 * 把生成好的小图（约 40KB）顺手写回 NAS，换平板 / 重装 / 清缓存时只读小图，秒开。
 *
 * 指纹 = sha1(相对路径 + 文件大小 + 拍摄时间)：原片被替换或重拍时指纹变化，旧图自然失效。
 */
class NasThumbMirror(private val fs: SmbFs, private val rootSupplier: () -> String) {

    /** 远端目录里已有的文件名清单；null 表示这次运行还没拉过 */
    private val remoteNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var listed = false

    /** 写能力探测结果：true 可写、false 只读降级 —— 探测过才赋值 */
    var writable: Boolean? = null
        private set

    /** 只在确实需要时联网 */
    private var dirChecked = false

    private fun dir(): String {
        val root = rootSupplier().trim().trim('/')
        return if (root.isEmpty()) ".dji-album/thumbs" else "$root/.dji-album/thumbs"
    }

    fun nameFor(item: MediaItem): String =
        ThumbStore.sha1("${item.path}|${item.size}|${item.takenAt}") + ".jpg"

    /** 拉一次远端清单（一次 SMB list，几百条，几十毫秒） */
    fun ensureListed(): Boolean {
        if (!dirChecked) {
            dirChecked = true
            val ok = fs.mkdirs(dir())
            writable = ok
            android.util.Log.i("DJI-THUMB", "nas mirror dir='${dir()}' writable=$ok")
        }
        if (listed) return true
        val entries = fs.listNames(dir())
        remoteNames.clear()
        entries.forEach { if (!it.isDirectory) remoteNames += it.name }
        listed = true
        android.util.Log.i("DJI-THUMB", "nas mirror listed=${remoteNames.size}")
        return writable == true
    }

    fun has(name: String): Boolean = listed && remoteNames.contains(name)

    /** 从 NAS 取一张小图；拿到就直接写成本地缓存（本地名按路径，远端名按指纹） */
    fun fetch(remoteName: String, localName: String, thumbs: ThumbStore): Boolean {
        val bytes = fs.readBytes(dir() + "/" + remoteName, 2 * 1024 * 1024)
        if (bytes.size < 500) return false
        val out: File = thumbs.file(localName)
        val ok = runCatching { out.writeBytes(bytes); out.length() > 500 }.getOrDefault(false)
        if (!ok) remoteNames.remove(remoteName)
        return ok
    }

    /** 本地生成完一张后，补一份到 NAS */
    fun push(localName: String, remoteName: String, thumbs: ThumbStore): Boolean {
        if (writable != true) return false
        val f = thumbs.file(localName)
        if (!f.isFile || f.length() < 500) return false
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return false
        val ok = fs.writeBytes(dir() + "/" + remoteName, bytes)
        if (ok) remoteNames += remoteName
        return ok
    }

    /**
     * 孤儿清理：远端有、但索引里已经没有对应指纹的，删掉。
     * 只在扫描成功（keep 非空）时调用，避免断网/空索引误删。
     */
    fun pruneOrphans(keep: Set<String>): Int {
        if (!listed || writable != true || keep.isEmpty()) return 0
        val drop = remoteNames.filter { it !in keep }
        if (drop.isEmpty()) return 0
        var deleted = 0
        drop.forEach { name ->
            if (fs.delete(dir() + "/" + name)) {
                remoteNames.remove(name)
                deleted++
            }
        }
        android.util.Log.i("DJI-THUMB", "nas mirror pruned=$deleted kept=${keep.size}")
        return deleted
    }

    fun remoteCount(): Int = remoteNames.size

    fun reset() {
        listed = false
        dirChecked = false
        remoteNames.clear()
    }

    /** 手动清空 NAS 镜像（设置页按钮） */
    fun clearAll(): Int {
        if (!ensureListed()) {
            // 只读/不可写时仍可尝试删，失败就算了
        }
        val names = remoteNames.toList()
        var deleted = 0
        names.forEach { if (fs.delete(dir() + "/" + it)) deleted++ }
        remoteNames.clear()
        return deleted
    }
}
