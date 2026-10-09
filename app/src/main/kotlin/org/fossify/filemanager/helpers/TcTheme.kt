package org.fossify.filemanager.helpers

import android.content.Context

/** Light / dark switch for the Total Commander look. The choice is stored in its own SharedPreferences file. */
object TcTheme {
    private const val PREFS = "tc_theme"
    private const val KEY_LIGHT = "light"

    fun isLight(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT, false)

    fun setLight(context: Context, light: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIGHT, light).apply()
    }

    fun background(c: Context) = if (isLight(c)) 0xFFFAFAFA.toInt() else 0xFF312F32.toInt()
    fun text(c: Context) = if (isLight(c)) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
    fun topBar(c: Context) = if (isLight(c)) 0xFFF5F5F5.toInt() else 0xFF201E21.toInt()
    fun navBar(c: Context) = topBar(c)
    // dark: black; light: the gray of the real Total Commander (#727272)
    fun statusBar(c: Context) = if (isLight(c)) 0xFF727272.toInt() else 0xFF000000.toInt()
}
