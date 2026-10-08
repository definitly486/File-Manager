package org.fossify.filemanager

import com.github.ajalt.reprint.core.Reprint
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        Reprint.initialize(this)
        applyCommanderTheme()
    }

    // Fixed dark blue-gray look (Total Commander style, gray), independent of the system theme.
    private fun applyCommanderTheme() {
        baseConfig.apply {
            isSystemThemeEnabled = false
            backgroundColor = BACKGROUND_COLOR
            textColor = TEXT_COLOR
            primaryColor = PRIMARY_COLOR
        }
    }

    private companion object {
        const val BACKGROUND_COLOR = 0xFF2A2E33.toInt()
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val PRIMARY_COLOR = 0xFF2A2E33.toInt()
    }
}
