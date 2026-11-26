package com.wei.djialbum.model

/** 与桌面版 shared/types.ts 对齐的数据模型 */
enum class MediaType { VIDEO, PHOTO }

data class MediaItem(
    val path: String,
    val name: String,
    val type: MediaType,
    val dirDate: String,
    val size: Long,
    val takenAt: Long,
    val thumb: String?,
    val lrfPath: String?,
    val fav: Boolean,
    val duration: Double?,
    val metaAt: Long = 0
)

data class TrashItem(
    val path: String,
    val name: String,
    val type: MediaType,
    val dirDate: String,
    val size: Long,
    val thumb: String?,
    val deletedAt: Long
)

data class NasConfig(
    val host: String = "",
    val share: String = "",
    val username: String = "",
    val rootDir: String = "",
    val password: String = "",
    val configured: Boolean = false
) {
    val rootPath: String
        get() = listOf(share, rootDir.trim('/').replace('\\', '/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")

    fun isValid(): Boolean = host.isNotBlank() && share.isNotBlank() && username.isNotBlank()
}

data class MediaMeta(
    val path: String,
    val name: String,
    val type: MediaType,
    val dirDate: String,
    val size: Long,
    val takenAt: Long,
    val lrfPath: String?,
    val lrfSize: Long?,
    val duration: Double?,
    val width: Int?,
    val height: Int?,
    val bitrate: Long?,
    val vcodec: String?,
    val exists: Boolean
)

data class ScanResult(val videos: Int, val photos: Int, val dirs: Int)

data class Progress(val done: Int, val total: Int, val failed: Int = 0) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

data class PurgeFile(val path: String, val size: Long, val exists: Boolean)

data class PurgePlanItem(
    val path: String,
    val name: String,
    val type: MediaType,
    val size: Long,
    val thumb: String?,
    val files: List<PurgeFile>
)

data class PurgePreview(
    val items: List<PurgePlanItem>,
    val totalFiles: Int,
    val totalBytes: Long
)

data class PurgeResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val bytes: Long,
    val errors: List<String>
)

data class AppInfo(
    val version: String,
    val files: Int,
    val videos: Int,
    val photos: Int,
    val favorites: Int,
    val trash: Int,
    val totalBytes: Long,
    val thumbMissing: Int,
    val cacheFiles: Int,
    val cacheBytes: Long,
    val cacheLimitBytes: Long,
    val root: String
)
