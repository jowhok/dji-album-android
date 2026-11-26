package com.wei.djialbum.data

import android.content.Context
import com.wei.djialbum.core.Crypto
import com.wei.djialbum.model.NasConfig

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dji_album", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "").orEmpty()
        set(v) = sp.edit().putString("host", v).apply()

    var share: String
        get() = sp.getString("share", "").orEmpty()
        set(v) = sp.edit().putString("share", v).apply()

    var username: String
        get() = sp.getString("username", "").orEmpty()
        set(v) = sp.edit().putString("username", v).apply()

    var rootDir: String
        get() = sp.getString("rootDir", "").orEmpty()
        set(v) = sp.edit().putString("rootDir", v).apply()

    var configured: Boolean
        get() = sp.getBoolean("configured", false)
        set(v) = sp.edit().putBoolean("configured", v).apply()

    var passwordEnc: String
        get() = sp.getString("passwordEnc", "").orEmpty()
        set(v) = sp.edit().putString("passwordEnc", v).apply()

    /** 主题：light / dark / system */
    var themeMode: String
        get() = sp.getString("themeMode", "system").orEmpty()
        set(v) = sp.edit().putString("themeMode", v).apply()

    /** 缩略图是否顺手镜像到 NAS（默认开，可在设置里关掉） */
    var nasThumbs: Boolean
        get() = sp.getBoolean("nasThumbs", true)
        set(v) = sp.edit().putBoolean("nasThumbs", v).apply()

    val password: String
        get() = Crypto.decrypt(passwordEnc)

    fun config(): NasConfig = NasConfig(
        host = host,
        share = share,
        username = username,
        rootDir = rootDir,
        password = password,
        configured = configured
    )

    fun saveConfig(cfg: NasConfig) {
        host = cfg.host.trim()
        share = cfg.share.trim()
        username = cfg.username.trim()
        rootDir = cfg.rootDir.trim().trim('/').replace('\\', '/')
        if (cfg.password.isNotEmpty()) passwordEnc = Crypto.encrypt(cfg.password)
        configured = true
    }
}
