package org.fossify.filemanager

import com.github.ajalt.reprint.core.Reprint
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.filemanager.helpers.TcTheme

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        Reprint.initialize(this)
        applyCommanderTheme()
    }

    // Total Commander look: dark by default, light after "Light -> Dark" in the overflow menu (see TcTheme).
    fun applyCommanderTheme() {
        baseConfig.apply {
            isSystemThemeEnabled = false
            backgroundColor = TcTheme.background(this@App)
            textColor = TcTheme.text(this@App)
            primaryColor = TcTheme.topBar(this@App)
        }
    }
}
