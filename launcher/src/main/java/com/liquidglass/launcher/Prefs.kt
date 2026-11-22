package com.liquidglass.launcher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("glass_home", Context.MODE_PRIVATE)

    var dock: List<String>?
        get() = sp.getString("dock", null)?.split("|")?.filter { it.isNotBlank() }
        set(value) = sp.edit().putString("dock", value?.joinToString("|")).apply()

    var wallpaperUri: String?
        get() = sp.getString("wallpaper", null)
        set(value) = sp.edit().putString("wallpaper", value).apply()

    /** 0 = Clear, 1 = Blue, 2 = Pink, 3 = Dark, 4 = Light */
    var tintStyle: Int
        get() = sp.getInt("tint", 0)
        set(value) = sp.edit().putInt("tint", value).apply()

    var showLabels: Boolean
        get() = sp.getBoolean("labels", true)
        set(value) = sp.edit().putBoolean("labels", value).apply()
}
