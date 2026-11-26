package com.wei.djialbum.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/** 缩略图磁盘缓存：文件名 = NAS 路径的 sha1，容量上限 512MB，按最旧优先淘汰 */
class ThumbStore(context: Context) {

    val dir: File = File(context.filesDir, "thumbs").apply { mkdirs() }
    val limitBytes: Long = 512L * 1024 * 1024

    fun nameFor(path: String): String = sha1(path) + ".jpg"

    fun file(name: String): File = File(dir, name)

    fun save(name: String, bitmap: Bitmap): Boolean = runCatching {
        val out = file(name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }
        out.length() > 500
    }.getOrDefault(false)

    fun isValid(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val f = file(name)
        return f.isFile && f.length() > 500
    }

    fun delete(name: String?) {
        if (name.isNullOrEmpty()) return
        runCatching { file(name).delete() }
    }

    fun trim(): Pair<Int, Long> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return 0 to 0L
        var deleted = 0
        var freed = 0L
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total - freed <= limitBytes) return@forEach
            val len = f.length()
            if (f.delete()) {
                deleted++
                freed += len
            }
        }
        return deleted to freed
    }

    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList().orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clear(): Pair<Int, Long> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList().orEmpty()
        var deleted = 0
        var bytes = 0L
        files.forEach { f ->
            val len = f.length()
            if (f.delete()) {
                deleted++
                bytes += len
            }
        }
        return deleted to bytes
    }

    companion object {
        fun sha1(s: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
