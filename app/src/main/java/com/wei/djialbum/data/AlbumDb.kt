package com.wei.djialbum.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wei.djialbum.model.AppInfo
import com.wei.djialbum.model.MediaItem
import com.wei.djialbum.model.MediaType
import com.wei.djialbum.model.TrashItem

/**
 * 索引库沿用桌面版 schema（files / favorites / trash），
 * 方便两端对同一批 NAS 数据给出同样的结果。
 */
class AlbumDb(context: Context) : SQLiteOpenHelper(context, "album.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS files (
              path TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              type TEXT NOT NULL,
              dir_date TEXT NOT NULL,
              size INTEGER NOT NULL DEFAULT 0,
              taken_at INTEGER NOT NULL DEFAULT 0,
              thumb TEXT,
              lrf_path TEXT,
              indexed_at INTEGER NOT NULL,
              duration REAL,
              width INTEGER,
              height INTEGER,
              bitrate INTEGER,
              vcodec TEXT,
              meta_at INTEGER NOT NULL DEFAULT 0,
              thumb_tries INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_date ON files(dir_date DESC, taken_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_type ON files(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_meta ON files(meta_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (path TEXT PRIMARY KEY, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS trash (path TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE files ADD COLUMN thumb_tries INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    fun inTransaction(block: () -> Unit) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            block()
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    data class MetaFields(
        val duration: Double?,
        val width: Int?,
        val height: Int?,
        val bitrate: Long?,
        val vcodec: String?
    )

    fun metaOf(path: String): MetaFields {
        readableDatabase.rawQuery(
            "SELECT duration, width, height, bitrate, vcodec FROM files WHERE path = ?",
            arrayOf(path)
        ).use { c ->
            if (!c.moveToFirst()) return MetaFields(null, null, null, null, null)
            return MetaFields(
                duration = if (c.isNull(0)) null else c.getDouble(0),
                width = if (c.isNull(1)) null else c.getInt(1),
                height = if (c.isNull(2)) null else c.getInt(2),
                bitrate = if (c.isNull(3)) null else c.getLong(3),
                vcodec = c.getString(4)
            )
        }
    }

    fun upsertFile(
        path: String,
        name: String,
        type: MediaType,
        dirDate: String,
        size: Long,
        takenAt: Long,
        lrfPath: String?
    ) {
        val cv = ContentValues().apply {
            put("name", name)
            put("type", if (type == MediaType.VIDEO) "video" else "photo")
            put("dir_date", dirDate)
            put("size", size)
            put("taken_at", takenAt)
            put("lrf_path", lrfPath)
            put("indexed_at", System.currentTimeMillis())
        }
        // 先 UPDATE 再 INSERT：CONFLICT_REPLACE 会把整行重写，thumb / duration / meta_at 全被清空，
        // 结果每次重新扫描都要重做全部缩略图与元数据。
        val updated = writableDatabase.update("files", cv, "path = ?", arrayOf(path))
        if (updated == 0) {
            cv.put("path", path)
            writableDatabase.insertWithOnConflict("files", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun deleteFilesNotIn(paths: Collection<String>) {
        if (paths.isEmpty()) {
            writableDatabase.delete("files", null, null)
            return
        }
        val keep = paths.toSet()
        val stale = mutableListOf<String>()
        writableDatabase.rawQuery("SELECT path FROM files", null).use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0)
                if (p !in keep) stale += p
            }
        }
        if (stale.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            stale.forEach {
                writableDatabase.delete("files", "path = ?", arrayOf(it))
                writableDatabase.delete("favorites", "path = ?", arrayOf(it))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun updateThumb(path: String, thumb: String?) {
        writableDatabase.update(
            "files",
            ContentValues().apply { put("thumb", thumb) },
            "path = ?",
            arrayOf(path)
        )
    }

    fun clearThumbRefs() {
        writableDatabase.execSQL("UPDATE files SET thumb = NULL")
    }

    fun updateMeta(
        path: String,
        duration: Double?,
        width: Int?,
        height: Int?,
        bitrate: Long?,
        vcodec: String?
    ) {
        val cv = ContentValues().apply {
            put("duration", duration)
            put("width", width)
            put("height", height)
            put("bitrate", bitrate)
            put("vcodec", vcodec)
            put("meta_at", System.currentTimeMillis())
        }
        writableDatabase.update("files", cv, "path = ?", arrayOf(path))
    }

    fun listMedia(type: String, sortBySize: Boolean, favoritesOnly: Boolean): List<MediaItem> {
        val order = if (sortBySize) "f.dir_date DESC, f.size DESC" else "f.dir_date DESC, f.taken_at DESC"
        val favClause = if (favoritesOnly) "fav.path IS NOT NULL" else "1"
        val sql = """
            SELECT f.path, f.name, f.type, f.dir_date, f.size, f.taken_at, f.thumb, f.lrf_path,
                   f.duration, f.meta_at,
                   CASE WHEN fav.path IS NOT NULL THEN 1 ELSE 0 END AS fav
            FROM files f
            LEFT JOIN favorites fav ON fav.path = f.path
            LEFT JOIN trash tr ON tr.path = f.path
            WHERE tr.path IS NULL
              AND (? = 'all' OR f.type = ?)
              AND ($favClause)
            ORDER BY $order
        """.trimIndent()
        val rows = mutableListOf<MediaItem>()
        readableDatabase.rawQuery(sql, arrayOf(type, type)).use { c ->
            while (c.moveToNext()) {
                rows += MediaItem(
                    path = c.getString(0),
                    name = c.getString(1),
                    type = if (c.getString(2) == "video") MediaType.VIDEO else MediaType.PHOTO,
                    dirDate = c.getString(3),
                    size = c.getLong(4),
                    takenAt = c.getLong(5),
                    thumb = c.getString(6),
                    lrfPath = c.getString(7),
                    fav = c.getInt(10) == 1,
                    duration = if (c.isNull(8)) null else c.getDouble(8),
                    metaAt = c.getLong(9)
                )
            }
        }
        return rows
    }

    fun findItem(path: String): MediaItem? = listMedia("all", false, false).firstOrNull { it.path == path }

    fun toggleFavorite(path: String): Boolean {
        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE path = ? LIMIT 1", arrayOf(path)
        ).use { it.moveToFirst() }
        if (exists) {
            writableDatabase.delete("favorites", "path = ?", arrayOf(path))
            return false
        }
        writableDatabase.insertWithOnConflict(
            "favorites", null,
            ContentValues().apply {
                put("path", path)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        return true
    }

    fun setFavorite(paths: Collection<String>, fav: Boolean) {
        writableDatabase.beginTransaction()
        try {
            paths.forEach { p ->
                if (fav) {
                    writableDatabase.insertWithOnConflict(
                        "favorites", null,
                        ContentValues().apply {
                            put("path", p)
                            put("created_at", System.currentTimeMillis())
                        },
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                } else {
                    writableDatabase.delete("favorites", "path = ?", arrayOf(p))
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun moveToTrash(paths: Collection<String>) {
        writableDatabase.beginTransaction()
        try {
            paths.forEach { p ->
                writableDatabase.insertWithOnConflict(
                    "trash", null,
                    ContentValues().apply {
                        put("path", p)
                        put("deleted_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun listTrash(): List<TrashItem> {
        val rows = mutableListOf<TrashItem>()
        readableDatabase.rawQuery(
            """
            SELECT f.path, f.name, f.type, f.dir_date, f.size, f.thumb, t.deleted_at
            FROM trash t JOIN files f ON f.path = t.path
            ORDER BY t.deleted_at DESC
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                rows += TrashItem(
                    path = c.getString(0),
                    name = c.getString(1),
                    type = if (c.getString(2) == "video") MediaType.VIDEO else MediaType.PHOTO,
                    dirDate = c.getString(3),
                    size = c.getLong(4),
                    thumb = c.getString(5),
                    deletedAt = c.getLong(6)
                )
            }
        }
        return rows
    }

    fun restoreFromTrash(paths: Collection<String>) {
        writableDatabase.beginTransaction()
        try {
            paths.forEach { writableDatabase.delete("trash", "path = ?", arrayOf(it)) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun trashEntryFor(path: String): MediaItem? {
        readableDatabase.rawQuery(
            """
            SELECT f.path, f.name, f.type, f.dir_date, f.size, f.taken_at, f.thumb, f.lrf_path,
                   f.duration, f.meta_at, 0
            FROM files f JOIN trash t ON t.path = f.path WHERE f.path = ?
            """.trimIndent(), arrayOf(path)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return MediaItem(
                path = c.getString(0),
                name = c.getString(1),
                type = if (c.getString(2) == "video") MediaType.VIDEO else MediaType.PHOTO,
                dirDate = c.getString(3),
                size = c.getLong(4),
                takenAt = c.getLong(5),
                thumb = c.getString(6),
                lrfPath = c.getString(7),
                fav = false,
                duration = if (c.isNull(8)) null else c.getDouble(8),
                metaAt = c.getLong(9)
            )
        }
    }

    fun purgeRecords(paths: Collection<String>) {
        writableDatabase.beginTransaction()
        try {
            paths.forEach {
                writableDatabase.delete("files", "path = ?", arrayOf(it))
                writableDatabase.delete("favorites", "path = ?", arrayOf(it))
                writableDatabase.delete("trash", "path = ?", arrayOf(it))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 待生成缩略图的条目：失败过的重试上限 3 次，避免每次启动反复啃同一批坏文件。 */
    fun itemsPendingThumb(): List<MediaItem> {
        val rows = mutableListOf<MediaItem>()
        readableDatabase.rawQuery(
            """
            SELECT f.path, f.name, f.type, f.dir_date, f.size, f.taken_at, f.thumb, f.lrf_path,
                   f.duration, f.meta_at, CASE WHEN fav.path IS NOT NULL THEN 1 ELSE 0 END
            FROM files f LEFT JOIN favorites fav ON fav.path = f.path
            WHERE (f.thumb IS NULL OR f.thumb = '') AND f.thumb_tries < 3
            ORDER BY f.dir_date DESC, f.taken_at DESC
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    MediaItem(
                        path = c.getString(0),
                        name = c.getString(1),
                        type = if (c.getString(2) == "video") MediaType.VIDEO else MediaType.PHOTO,
                        dirDate = c.getString(3),
                        size = c.getLong(4),
                        takenAt = c.getLong(5),
                        thumb = c.getString(6),
                        lrfPath = c.getString(7),
                        duration = if (c.isNull(8)) null else c.getDouble(8),
                        fav = c.getInt(10) == 1
                    )
                )
            }
        }
        return rows
    }

    /** 全部文件的（路径 / 大小 / 拍摄时间）三元组，用来算 NAS 缩略图镜像的保留集 */
    fun thumbFingerprintSources(): List<Triple<String, Long, Long>> {
        val rows = mutableListOf<Triple<String, Long, Long>>()
        readableDatabase.rawQuery("SELECT path, size, taken_at FROM files", null).use { c ->
            while (c.moveToNext()) rows += Triple(c.getString(0), c.getLong(1), c.getLong(2))
        }
        return rows
    }

    fun bumpThumbTry(path: String) {        runCatching {
            writableDatabase.execSQL(
                "UPDATE files SET thumb_tries = thumb_tries + 1 WHERE path = ?",
                arrayOf(path)
            )
        }
    }

    fun itemsPendingMeta(): List<MediaItem> {
        val rows = mutableListOf<MediaItem>()
        readableDatabase.rawQuery(
            """
            SELECT f.path, f.name, f.type, f.dir_date, f.size, f.taken_at, f.thumb, f.lrf_path,
                   f.duration, f.meta_at, CASE WHEN fav.path IS NOT NULL THEN 1 ELSE 0 END
            FROM files f LEFT JOIN favorites fav ON fav.path = f.path
            WHERE f.meta_at = 0
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                rows += MediaItem(
                    path = c.getString(0),
                    name = c.getString(1),
                    type = if (c.getString(2) == "video") MediaType.VIDEO else MediaType.PHOTO,
                    dirDate = c.getString(3),
                    size = c.getLong(4),
                    takenAt = c.getLong(5),
                    thumb = c.getString(6),
                    lrfPath = c.getString(7),
                    fav = c.getInt(10) == 1,
                    duration = if (c.isNull(8)) null else c.getDouble(8),
                    metaAt = c.getLong(9)
                )
            }
        }
        return rows
    }

    fun stats(cacheFiles: Int, cacheBytes: Long, cacheLimit: Long, root: String): AppInfo {
        fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        fun sum(sql: String): Long = readableDatabase.rawQuery(sql, null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        return AppInfo(
            version = "1.0.0-android",
            files = count("SELECT COUNT(*) FROM files"),
            videos = count("SELECT COUNT(*) FROM files WHERE type='video'"),
            photos = count("SELECT COUNT(*) FROM files WHERE type='photo'"),
            favorites = count("SELECT COUNT(*) FROM favorites"),
            trash = count("SELECT COUNT(*) FROM trash"),
            totalBytes = sum("SELECT COALESCE(SUM(size),0) FROM files"),
            thumbMissing = count("SELECT COUNT(*) FROM files WHERE thumb IS NULL"),
            cacheFiles = cacheFiles,
            cacheBytes = cacheBytes,
            cacheLimitBytes = cacheLimit,
            root = root
        )
    }

    fun allFavoritePaths(): Set<String> {
        val s = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT path FROM favorites", null).use { c ->
            while (c.moveToNext()) s += c.getString(0)
        }
        return s
    }
}
