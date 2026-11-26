package com.wei.djialbum.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.wei.djialbum.data.SmbByteReader
import com.wei.djialbum.data.SmbFs
import java.io.IOException

/**
 * 让 ExoPlayer 直接从 SMB 随机读，无需本地 HTTP 代理。
 * URI 形如 dji-smb://media?p=<urlencoded 相对路径>
 *
 * 关键：ExoPlayer 每次 read 只有几十 KB，如果每次都发一次 SMB 请求，
 * 一秒钟要几百个往返（Wi-Fi 下每趟 10ms 左右），带宽会被延迟吃光。
 * 所以这里做一个 1MB 的读前缓冲窗口，顺序播放时一次往返喂满一整块。
 */
class SmbDataSource(private val fsProvider: () -> SmbFs) : BaseDataSource(true) {

    private var readers: List<SmbByteReader> = emptyList()
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var fileSize = 0L

    private var cacheOffset = -1L
    private var cache: ByteArray? = null
    private var cacheLen = 0

    // 吞吐统计
    private var statBytes = 0L
    private var statStart = 0L
    private var fillCount = 0
    private var fillMsTotal = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = dataSpec.uri.getQueryParameter("p")
            ?: throw IOException("缺少媒体路径")
        val fs = fsProvider()
        fileSize = fs.size(path)
        readers = (1..4).mapNotNull { runCatching { fs.openReader(path) }.getOrNull() }
        require(readers.isNotEmpty()) { "无法打开媒体文件" }
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (fileSize - position).coerceAtLeast(0)
        }
        cacheOffset = -1L
        cache = null
        cacheLen = 0
        statBytes = 0L
        statStart = System.currentTimeMillis()
        fillCount = 0
        fillMsTotal = 0L
        uri = dataSpec.uri
        transferStarted(dataSpec)
        Log.i("DJI-PLAY", "open ${path.substringAfterLast('/')} size=${fileSize} from=$position")
        return remaining
    }

    private fun fillFrom(from: Long) {
        val size = minOf(BLOCK.toLong(), fileSize - from).toInt().coerceAtLeast(1)
        val buf = ByteArray(size)
        val t0 = System.currentTimeMillis()
        var got = 0
        val pool = readers
        if (pool.size > 1 && size >= 512 * 1024) {
            // 单条 SMB 流被 NAS 的往返延迟限制在十几 MB/s，多句柄并行读同一块能明显提速
            val parts = pool.size
            val partSize = size / parts
            val err = arrayOfNulls<Throwable>(parts)
            val threads = (0 until parts).map { i ->
                val off = i * partSize
                val len = if (i == parts - 1) size - off else partSize
                Thread {
                    runCatching { readInto(pool[i], from + off, buf, off, len) }
                        .onFailure { err[i] = it }
                }.apply { start() }
            }
            threads.forEach { it.join() }
            err.forEach { if (it != null) throw IOException(it) }
            got = size
        } else {
            while (got < size) {
                val n = pool.first().read(from + got, buf, got, size - got)
                if (n <= 0) break
                got += n
            }
        }
        val cost = System.currentTimeMillis() - t0
        fillCount++
        fillMsTotal += cost
        cacheOffset = from
        cache = buf
        cacheLen = got
        if (cost > 400) Log.w("DJI-PLAY", "prefetch slow ${got / 1024}KB in ${cost}ms")
    }

    private fun readInto(r: SmbByteReader, from: Long, buf: ByteArray, offset: Int, len: Int) {
        var got = 0
        while (got < len) {
            val n = r.read(from + got, buf, offset + got, len - got)
            if (n <= 0) break
            got += n
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        var produced = 0
        while (produced < want) {
            if (cache == null || position < cacheOffset || position >= cacheOffset + cacheLen) {
                fillFrom(position)
                if (cacheLen <= 0) break
            }
            val avail = minOf((cacheOffset + cacheLen - position).toInt(), want - produced)
            if (avail <= 0) break
            System.arraycopy(cache!!, (position - cacheOffset).toInt(), buffer, offset + produced, avail)
            position += avail
            remaining -= avail
            produced += avail
        }
        if (produced == 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(produced)
        statBytes += produced
        val elapsed = System.currentTimeMillis() - statStart
        if (statBytes >= 8L * 1024 * 1024 && elapsed > 0) {
            Log.i(
                "DJI-PLAY",
                "throughput ${statBytes / 1024 / 1024}MB in ${elapsed}ms = " +
                    "${(statBytes * 1000 / elapsed / 1024)}KB/s, prefetch avg " +
                    "${fillMsTotal / fillCount.coerceAtLeast(1)}ms"
            )
            statBytes = 0
            statStart = System.currentTimeMillis()
            fillCount = 0
            fillMsTotal = 0
        }
        return produced
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        readers.forEach { runCatching { it.close() } }
        readers = emptyList()
        cache = null
        cacheLen = 0
        cacheOffset = -1L
        transferEnded()
        Log.i("DJI-PLAY", "close")
    }

    private companion object {
        const val BLOCK = 4 shl 20
    }
}

class SmbDataSourceFactory(private val fsProvider: () -> SmbFs) : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource(fsProvider)
}
