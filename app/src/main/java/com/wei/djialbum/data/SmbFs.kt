package com.wei.djialbum.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.wei.djialbum.model.NasConfig
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long
)

/**
 * SMB 2/3 访问层（基于 smbj）。负责连接复用、目录列举、随机读与删除。
 * 所有调用都应在 IO 线程执行。
 */
class SmbFs(private val configProvider: () -> NasConfig) {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    private var current: NasConfig? = null

    @Synchronized
    fun share(): DiskShare {
        val cfg = configProvider()
        require(cfg.isValid()) { "请先在设置页填写 NAS 主机、共享名与用户名" }
        val cached = disk
        if (cached != null && current == cfg && connection?.isConnected == true) return cached
        disconnect()
        val smbConfig = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(smbConfig)
        val conn = c.connect(cfg.host.trim())
        android.util.Log.i("DJI-SMB", "connected ${cfg.host}")
        val auth = AuthenticationContext(cfg.username, cfg.password.toCharArray(), cfg.host.trim())
        val sess = conn.authenticate(auth)
        val tree = sess.connectShare(cfg.share.trim())
        if (tree !is DiskShare) throw IOException("共享 ${cfg.share} 不是磁盘共享")
        client = c
        connection = conn
        session = sess
        disk = tree
        current = cfg
        return tree
    }

    @Synchronized
    fun disconnect() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        disk = null
        session = null
        connection = null
        client = null
        current = null
    }

    fun invalidate() = disconnect()

    /**
     * 共享连接会被 NAS 空闲回收，缓存的 DiskShare 可能已失效。
     * 统一在失败后断开重连一次，避免扫描/浏览因一句话 "already been closed" 直接失败。
     */
    private fun <T> withShare(block: (DiskShare) -> T): T {
        return try {
            block(share())
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            android.util.Log.w("DJI-SMB", "retry after: ${e.message}")
            synchronized(this) { disconnect() }
            block(share())
        }
    }

    /** 相对索引路径（用 / 分隔，不含共享名）→ SMB 路径（\ 分隔） */
    fun smbPath(relative: String): String = relative.replace('/', '\\').trimStart('\\')

    fun exists(relative: String): Boolean {
        val p = smbPath(relative)
        return runCatching { withShare { it.folderExists(p) || it.fileExists(p) } }.getOrDefault(false)
    }

    fun isDirectory(relative: String): Boolean =
        runCatching { withShare { it.folderExists(smbPath(relative)) } }.getOrDefault(false)

    fun size(relative: String): Long =
        withShare { it.getFileInformation(smbPath(relative)).standardInformation.endOfFile }

    fun list(relative: String): List<SmbEntry> {
        val p = smbPath(relative)
        val t0 = System.currentTimeMillis()
        val entries = withShare { it.list(p.ifEmpty { "\\" }) }
        android.util.Log.i("DJI-SMB", "list '$p' -> ${entries.size} in ${System.currentTimeMillis() - t0}ms")
        return entries.mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L
            SmbEntry(
                name = name,
                isDirectory = isDir,
                size = if (isDir) 0L else info.endOfFile,
                modifiedAt = runCatching { info.lastWriteTime.toEpochMillis() }
                    .getOrElse { System.currentTimeMillis() }
            )
        }
    }

    fun openFile(relative: String): File = withShare { d ->
        d.openFile(
            smbPath(relative),
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    }

    fun openReader(relative: String): SmbByteReader = withShare { d ->
        val f = d.openFile(
            smbPath(relative),
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        SmbByteReader(f, d.getFileInformation(smbPath(relative)).standardInformation.endOfFile)
    }

    fun inputStream(relative: String): InputStream = openFile(relative).inputStream

    /** 递归建目录（SMB 的 mkdir 不会自动建父级）；已存在视为成功 */
    fun mkdirs(relative: String): Boolean = runCatching {
        withShare { d ->
            val p = smbPath(relative)
            if (d.folderExists(p)) true else {
                val parts = p.trim('\\').split('\\').filter { it.isNotEmpty() }
                var acc = ""
                var ok = true
                parts.forEach { seg ->
                    acc = if (acc.isEmpty()) seg else "$acc\\$seg"
                    if (!d.folderExists(acc)) {
                        ok = runCatching { d.mkdir(acc); true }.getOrDefault(false)
                    }
                }
                ok
            }
        }
    }.getOrDefault(false)

    /** 覆盖写一个小文件（缩略图镜像，几十 KB） */
    fun writeBytes(relative: String, bytes: ByteArray): Boolean = try {
        withShare { d ->
            val f = d.openFile(
                smbPath(relative),
                setOf(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            try {
                f.write(bytes, 0L, 0, bytes.size)
                true
            } finally {
                runCatching { f.close() }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("DJI-SMB", "write '$relative' failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** 只读前 limit 字节（缩略图镜像都是小文件，一次读完） */
    fun readBytes(relative: String, limit: Int): ByteArray = runCatching {
        val size = size(relative)
        if (size <= 0 || size > limit) {
            ByteArray(0)
        } else {
            val out = ByteArray(size.toInt())
            val reader = openReader(relative)
            try {
                var off = 0
                while (off < out.size) {
                    val n = reader.read(off.toLong(), out, off, out.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == out.size) out else out.copyOf(off)
            } finally {
                runCatching { reader.close() }
            }
        }
    }.getOrDefault(ByteArray(0))

    /** 列目录下的文件名（不递归），目录不存在返回空表 */
    fun listNames(relative: String): List<SmbEntry> = runCatching { list(relative) }.getOrDefault(emptyList())

    fun delete(relative: String): Boolean {
        return runCatching {
            withShare { it.rm(smbPath(relative)) }
            true
        }.getOrDefault(false)
    }

    fun testConnection(cfg: NasConfig) {
        val smbConfig = SmbConfig.builder().withTimeout(10, TimeUnit.SECONDS).build()
        val c = SMBClient(smbConfig)
        try {
            val conn = c.connect(cfg.host.trim())
            val sess = conn.authenticate(
                AuthenticationContext(cfg.username, cfg.password.toCharArray(), cfg.host.trim())
            )
            val tree = sess.connectShare(cfg.share.trim())
            if (tree !is DiskShare) throw IOException("共享 ${cfg.share} 不是磁盘共享")
            val root = smbPath(cfg.rootDir)
            if (root.isNotEmpty() && !tree.folderExists(root)) {
                throw IOException("目录不存在：${cfg.rootDir}")
            }
            runCatching { tree.close() }
            runCatching { sess.close() }
            runCatching { conn.close() }
        } finally {
            runCatching { c.close() }
        }
    }

    companion object {
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
    }
}

/** 基于 smbj File 的随机读对象，供缩略图/元数据/播放器使用 */
class SmbByteReader(private val file: File, val size: Long) : Closeable {

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || position >= size) return -1
        val want = minOf(length.toLong(), size - position).toInt()
        var total = 0
        while (total < want) {
            val chunk = minOf(want - total, 4 shl 20)
            val n = file.read(buffer, position + total, offset + total, chunk)
            if (n <= 0) break
            total += n
            if (n < chunk) break
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        runCatching { file.close() }
    }
}
