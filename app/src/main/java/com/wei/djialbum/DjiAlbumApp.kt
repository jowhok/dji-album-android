package com.wei.djialbum

import android.app.Application
import android.content.Context
import com.wei.djialbum.data.AlbumDb
import com.wei.djialbum.data.MediaRepository
import com.wei.djialbum.data.Prefs
import com.wei.djialbum.data.ThumbStore

class DjiAlbumApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var db: AlbumDb
        private set
    lateinit var thumbs: ThumbStore
        private set
    lateinit var repo: MediaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = AlbumDb(this)
        thumbs = ThumbStore(this)
        repo = MediaRepository(prefs, db, thumbs, this)
    }

    companion object {
        fun of(context: Context): DjiAlbumApp = context.applicationContext as DjiAlbumApp
    }
}
