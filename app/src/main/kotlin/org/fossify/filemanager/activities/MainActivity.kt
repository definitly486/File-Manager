package org.fossify.filemanager.activities

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.widget.ImageView
import android.widget.TextView
import androidx.viewpager.widget.ViewPager
import com.stericson.RootTools.RootTools
import me.grantland.widget.AutofitHelper
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getFilePublicUri
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.onTabSelectionChanged
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateBottomTabItemColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_GESTURE_VIEWS
import org.fossify.commons.helpers.LICENSE_GLIDE
import org.fossify.commons.helpers.LICENSE_PATTERN
import org.fossify.commons.helpers.LICENSE_REPRINT
import org.fossify.commons.helpers.LICENSE_ZIP4J
import org.fossify.commons.helpers.PERMISSION_WRITE_STORAGE
import org.fossify.commons.helpers.TAB_FILES
import org.fossify.commons.helpers.TAB_RECENT_FILES
import org.fossify.commons.helpers.TAB_STORAGE_ANALYSIS
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.filemanager.BuildConfig
import org.fossify.filemanager.R
import org.fossify.filemanager.adapters.ViewPagerAdapter
import org.fossify.filemanager.databinding.ActivityMainBinding
import org.fossify.filemanager.dialogs.ChangeSortingDialog
import org.fossify.filemanager.dialogs.ChangeViewTypeDialog
import org.fossify.filemanager.dialogs.InsertFilenameDialog
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.tryOpenPathIntent
import org.fossify.filemanager.fragments.ItemsFragment
import org.fossify.filemanager.fragments.MyViewPagerFragment
import org.fossify.filemanager.fragments.RecentsFragment
import org.fossify.filemanager.fragments.StorageFragment
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.interfaces.ItemOperationsListener
import java.io.File

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true
    
    companion object {
        private const val BACK_PRESS_TIMEOUT = 5000
        private const val PICKED_PATH = "picked_path"
    }

    // Bar colors follow the light/dark switch (see TcTheme).
    private val TOP_BAR_COLOR get() = org.fossify.filemanager.helpers.TcTheme.topBar(this)
    private val NAV_BAR_COLOR get() = org.fossify.filemanager.helpers.TcTheme.navBar(this)
    private val STATUS_BAR_COLOR get() = org.fossify.filemanager.helpers.TcTheme.statusBar(this)

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var wasBackJustPressed = false
    private var mTabsToShow = ArrayList<Int>()

    private var mStoredFontSize = 0
    private var mStoredDateFormat = ""
    private var mStoredTimeFormat = ""
    private var mStoredShowTabs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = STATUS_BAR_COLOR
        window.navigationBarColor = NAV_BAR_COLOR
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = org.fossify.filemanager.helpers.TcTheme.isLight(this)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        setContentView(binding.root)
        setupBlackStatusBar()
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        mTabsToShow = getTabsList()

        if (!config.wasStorageAnalysisTabAdded) {
            config.wasStorageAnalysisTabAdded = true
            if (config.showTabs and TAB_STORAGE_ANALYSIS == 0) {
                config.showTabs += TAB_STORAGE_ANALYSIS
            }
        }

        // Only the Files screen is used now: bottom tabs (Files / Recents / Storage) are removed
        mTabsToShow = arrayListOf(TAB_FILES)
        config.showTabs = TAB_FILES
        config.lastUsedViewPagerPage = 0

        storeStateVariables()
        setupTabs()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainHolder))

        if (savedInstanceState == null) {
            config.temporarilyShowHidden = false
            initFragments()
            tryInitFileManager()
            checkWhatsNewDialog()
            checkIfRootAvailable()
            checkInvalidFavorites()
        }
    }

    override fun onResume() {
        super.onResume()
        config.showTabs = TAB_FILES
        if (mStoredShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        refreshMenuItems()
        updateMenuColors()
        applyStatusBarIcons()
        setupTabColors()

        getAllFragments().forEach {
            it?.onResume(getProperTextColor())
        }

        if (mStoredFontSize != config.fontSize) {
            getAllFragments().forEach {
                (it as? ItemOperationsListener)?.setupFontSize()
            }
        }

        if (mStoredDateFormat != config.dateFormat || mStoredTimeFormat != getTimeFormat()) {
            getAllFragments().forEach {
                (it as? ItemOperationsListener)?.setupDateTimeFormat()
            }
        }

        if (binding.mainViewPager.adapter == null) {
            initFragments()
        }
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = binding.mainViewPager.currentItem
    }

    override fun onBackPressedCompat(): Boolean {
        val currentFragment = getCurrentFragment()
        if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            return true
        } else if (currentFragment is RecentsFragment || currentFragment is StorageFragment) {
            return false
        } else if (currentFragment is ItemsFragment && currentFragment.currentPath == org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
            // Already on home screen — exit (or require double back)
            if (!wasBackJustPressed && config.pressBackTwice) {
                wasBackJustPressed = true
                toast(R.string.press_back_again)
                Handler().postDelayed({
                    wasBackJustPressed = false
                }, BACK_PRESS_TIMEOUT.toLong())
                return true
            } else {
                appLockManager.lock()
                finish()
                return true
            }
        } else if ((currentFragment as ItemsFragment).getBreadcrumbs().getItemCount() <= 1) {
            // At volume root — go to home screen instead of exiting
            openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
            return true
        } else {
            currentFragment.getBreadcrumbs().removeBreadcrumb()
            openPath(currentFragment.getBreadcrumbs().getLastItem().path)
            return true
        }
    }

    fun refreshMenuItems() {
        val currentFragment = getCurrentFragment() ?: return
        val isCreateDocumentIntent = intent.action == Intent.ACTION_CREATE_DOCUMENT
        val currentViewType = config.getFolderViewType(currentFragment.currentPath)
        val favorites = config.favorites

        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.sort).isVisible = currentFragment is ItemsFragment
            findItem(R.id.change_view_type).isVisible = currentFragment !is StorageFragment

            findItem(R.id.add_favorite).isVisible = currentFragment is ItemsFragment && !favorites.contains(currentFragment.currentPath)
            findItem(R.id.remove_favorite).isVisible = currentFragment is ItemsFragment && favorites.contains(currentFragment.currentPath)
            findItem(R.id.go_to_favorite).isVisible = currentFragment is ItemsFragment && favorites.isNotEmpty()

            findItem(R.id.toggle_filename).isVisible = currentViewType == VIEW_TYPE_GRID && currentFragment !is StorageFragment
            findItem(R.id.go_home).isVisible = false // home button now lives in the list header
            findItem(R.id.set_as_home).isVisible = currentFragment is ItemsFragment && currentFragment.currentPath != config.homeFolder

            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden() && currentFragment !is StorageFragment
            findItem(R.id.stop_showing_hidden).isVisible = config.temporarilyShowHidden && currentFragment !is StorageFragment

            findItem(R.id.column_count).isVisible = currentViewType == VIEW_TYPE_GRID && currentFragment !is StorageFragment

            findItem(R.id.more_apps_from_us).isVisible = resources.getBoolean(R.bool.is_google_play_build)
            findItem(R.id.settings).isVisible = !isCreateDocumentIntent
            findItem(R.id.about).isVisible = !isCreateDocumentIntent
        }
    }



    /**
     * Replaces the system overflow (three dots) with a Total Commander style popup.
     */
    private fun installCustomOverflowMenu(toolbar: androidx.appcompat.widget.Toolbar) {
        // The overflow button is the last child of the toolbar's ActionMenuView. Do not search by class
        // name or by "More" text: names are obfuscated in release builds and the description is localized.
        fun findOverflowBtn(): android.view.View? {
            var menuView: androidx.appcompat.widget.ActionMenuView? = null
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is androidx.appcompat.widget.ActionMenuView) menuView = child
            }
            val mv = menuView ?: return null
            if (mv.childCount == 0) return null
            val last = mv.getChildAt(mv.childCount - 1)
            val desc = getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
            return if (last is android.widget.ImageView && last.contentDescription?.toString() == desc) last else null
        }

        fun hook() {
            val btn = findOverflowBtn() ?: return
            if (btn.getTag(R.id.main_coordinator) === OVERFLOW_HOOK_TAG) return
            btn.setTag(R.id.main_coordinator, OVERFLOW_HOOK_TAG)
            btn.setOnTouchListener(null) // drops AppCompat's ForwardingListener (it would open the system menu)
            btn.setOnClickListener {
                toolbar.hideOverflowMenu()
                showTcOverflowMenu(toolbar)
            }
        }

        // The button can be re-created whenever the menu is invalidated, so re-hook after every layout.
        hook()
        toolbar.viewTreeObserver.addOnGlobalLayoutListener { hook() }
    }

    private val OVERFLOW_HOOK_TAG = Any()
    private var overflowPopup: android.widget.PopupWindow? = null

    /**
     * Size and position measured from the real Total Commander (density 2.0 screenshots):
     *  - width 196dp, rows 48dp, text 16sp, 16dp side padding, corner radius 2dp
     *  - the popup overlaps the toolbar: its top-right corner is 4dp inside the toolbar's top-right corner
     *  - items: Exit, New folder..., Settings..., Light -> Dark (current -> target), Help (HTML)
     *  - light popup (#FAFAFA / text #202020) in light mode, dark popup (#312F32 / white) in dark mode
     */
    private fun showTcOverflowMenu(toolbar: androidx.appcompat.widget.Toolbar) {
        overflowPopup?.dismiss()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val light = org.fossify.filemanager.helpers.TcTheme.isLight(this)

        val menuWidth = dp(196)
        val rowHeight = dp(48)
        val margin = dp(4)

        val canCreateFolder = getCurrentFragment() is ItemsFragment
        val entries = arrayListOf<Pair<String, () -> Unit>>()
        entries += getString(R.string.tc_menu_exit) to { finishAndRemoveTask() }
        if (canCreateFolder) {
            entries += getString(R.string.tc_menu_new_folder) to { getItemsFragment().createNewFolder() }
        }
        entries += getString(R.string.tc_menu_settings) to { launchSettings() }
        entries += getString(if (light) R.string.tc_menu_theme_to_dark else R.string.tc_menu_theme_to_light) to {
            toggleTcTheme()
        }
        entries += getString(R.string.tc_menu_help) to { launchHelp() }

        val textColor = org.fossify.filemanager.helpers.TcTheme.text(this)
        val rippleValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val popup = android.widget.PopupWindow(this)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        entries.forEach { (title, action) ->
            column.addView(android.widget.TextView(this).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(textColor)
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding(dp(16), 0, dp(16), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (rippleValue.resourceId != 0) setBackgroundResource(rippleValue.resourceId)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
                )
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            })
        }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }

        val toolbarLoc = IntArray(2)
        toolbar.getLocationOnScreen(toolbarLoc)
        val top = toolbarLoc[1] + margin
        val available = resources.displayMetrics.heightPixels - top - margin

        popup.contentView = scroll
        popup.width = menuWidth
        popup.height = minOf(entries.size * rowHeight, available)
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(org.fossify.filemanager.helpers.TcTheme.background(this@MainActivity))
                cornerRadius = dp(2).toFloat()
            }
        )
        popup.elevation = dp(8).toFloat()
        popup.setOnDismissListener { overflowPopup = null }
        overflowPopup = popup

        // Gravity.END: right edge of popup = right edge of toolbar (xoff < 0 moves it inwards);
        // negative yoff lifts the popup from below the toolbar so that it overlaps it.
        popup.showAsDropDown(toolbar, -margin, -toolbar.height + margin, android.view.Gravity.END)
    }

    private fun toggleTcTheme() {
        val nowLight = org.fossify.filemanager.helpers.TcTheme.isLight(this)
        org.fossify.filemanager.helpers.TcTheme.setLight(this, !nowLight)
        (application as org.fossify.filemanager.App).applyCommanderTheme()
        recreate()
    }

    private fun launchHelp() {
        startActivity(Intent(this, HelpActivity::class.java))
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            val toolbar = requireToolbar()
            // Must set popupTheme before inflate + again after setupMenu (commons may reset it)
            toolbar.popupTheme = R.style.TcPopupMenuOverlay
            toolbar.inflateMenu(R.menu.menu)
            // Move the overflow (three-dots) button closer to the right edge
            // so it sits above the side arrows.
            // Commons MySearchMenu has paddingEnd on search_bar_container and
            // layout_marginEnd on top_toolbar — both push the ⋮ away from the edge.
            binding.searchBarContainer.setPadding(
                binding.searchBarContainer.paddingStart,
                binding.searchBarContainer.paddingTop,
                0,
                binding.searchBarContainer.paddingBottom
            )
            (toolbar.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let { lp ->
                lp.marginEnd = 0
                toolbar.layoutParams = lp
            }
            toolbar.setContentInsetsRelative(toolbar.contentInsetStart, 0)
            toolbar.setContentInsetEndWithActions(0)
            toolbar.setPadding(0, toolbar.paddingTop, 0, toolbar.paddingBottom)
            toggleHideOnScroll(false)
            setupMenu()
            toolbar.popupTheme = R.style.TcPopupMenuOverlay
            toolbar.post { installCustomOverflowMenu(toolbar) }


            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.searchQueryChanged("")
                }
                showToolbarTitleInsteadOfSearch()
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.searchQueryChanged(text)
            }

            requireToolbar().setOnMenuItemClickListener { menuItem ->
                // Close button always works (like original Total Commander)
                if (menuItem.itemId == R.id.exit_app) {
                    finishAndRemoveTask()
                    return@setOnMenuItemClickListener true
                }

                if (getCurrentFragment() == null) {
                    return@setOnMenuItemClickListener true
                }

                when (menuItem.itemId) {
                    R.id.search -> openSearchField()
                    R.id.go_home -> goHome()
                    R.id.go_to_favorite -> goToFavorite()
                    R.id.sort -> showSortingDialog()
                    R.id.add_favorite -> addFavorite()
                    R.id.remove_favorite -> removeFavorite()
                    R.id.toggle_filename -> toggleFilenameVisibility()
                    R.id.set_as_home -> setAsHome()
                    R.id.change_view_type -> changeViewType()
                    R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                    R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                    R.id.column_count -> changeColumnCount()
                    R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                    R.id.settings -> launchSettings()
                    R.id.about -> launchAbout()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }

        hideSearchBar()
    }

    private var statusBarCover: android.view.View? = null

    // Status bar icons: white on the black (dark) / gray (light) cover.
    private fun applyStatusBarIcons() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
    }

    // With targetSdk 36 the app is drawn edge-to-edge and window.statusBarColor is ignored,
    // so the area behind the system status bar shows whatever the layout paints there.
    // A black view pinned to the top, exactly as tall as the status bar, forces it to be black.
    private fun setupBlackStatusBar() {
        applyStatusBarIcons()

        if (statusBarCover != null) return

        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0

        val cover = android.view.View(this).apply {
            setBackgroundColor(STATUS_BAR_COLOR)
            elevation = 100f * resources.displayMetrics.density
            isClickable = false
            isFocusable = false
        }
        // Added to the decor view, not to binding.root: the root is padded by the status bar inset,
        // so a child of it would sit one status-bar height too low and cover the toolbar.
        (window.decorView as android.view.ViewGroup).addView(
            cover,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                fallbackHeight,
                android.view.Gravity.TOP
            )
        )
        statusBarCover = cover

        // Refine the height from the real window insets (cutouts, different devices)
        binding.root.post {
            val top = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top
            if (top != null && top > 0 && top != cover.layoutParams.height) {
                cover.layoutParams = cover.layoutParams.apply { height = top }
            }
        }
    }

    private var toolbarTitleView: android.widget.TextView? = null
    private var toolbarIconView: android.widget.ImageView? = null

    // Total Commander-style branding: a small app icon followed by the title.
    // The action icons remain on the right, while the search field stays hidden
    // until the search icon is tapped.
    private fun hideSearchBar() {
        binding.mainMenu.binding.apply {
            // Force the entire top header, including the icon/title area, to #201E21.
            searchBarContainer.setBackgroundColor(TOP_BAR_COLOR)
            // Remove right padding so the ⋮ sits at the edge (above the side arrows).
            searchBarContainer.setPadding(
                searchBarContainer.paddingStart,
                searchBarContainer.paddingTop,
                0,
                searchBarContainer.paddingBottom
            )
            topToolbarSearchIcon.visibility = android.view.View.GONE
            topToolbarSearch.visibility = android.view.View.GONE
            toolbarContainer.setBackgroundColor(TOP_BAR_COLOR)
            toolbarContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(TOP_BAR_COLOR)

            // Zero marginEnd on the toolbar itself (commons sets small_margin).
            (topToolbar.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let { lp ->
                lp.marginEnd = 0
                topToolbar.layoutParams = lp
            }
            // Drop the toolbar's start inset too: it is added to the toolbar's width and only steals
            // room from the title, which is stretched between the icon and the toolbar.
            topToolbar.contentInsetStartWithNavigation = 0
            topToolbar.setContentInsetsRelative(0, 0)
            topToolbar.setContentInsetEndWithActions(0)
            topToolbar.setPadding(0, topToolbar.paddingTop, 0, topToolbar.paddingBottom)

            if (toolbarTitleView == null) {
                val density = resources.displayMetrics.density
                // Larger icon without adaptive-icon padding so no halo/transition is visible
                val iconSize = (44 * density).toInt()
                val sidePadding = (2 * density).toInt()

                val iconView = android.widget.ImageView(this@MainActivity).apply {
                    id = android.view.View.generateViewId()
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        iconSize,
                        iconSize
                    ).apply {
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                        addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                        marginStart = sidePadding
                    }
                    setImageResource(R.drawable.total_commander_toolbar)
                    // Move the header icon 8dp to the left.
                    translationX = (-8 * density)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding(0, 0, 0, 0)
                    adjustViewBounds = true
                    contentDescription = getString(R.string.app_name)
                    isClickable = true
                    isFocusable = true
                    // Tap app icon → open TC home screen
                    setOnClickListener { goHome() }
                }
                toolbarContainer.addView(iconView)
                toolbarIconView = iconView

                val params = android.widget.RelativeLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                    addRule(android.widget.RelativeLayout.END_OF, iconView.id)
                    addRule(android.widget.RelativeLayout.START_OF, topToolbar.id)
                    // Gap between the header icon and the title (icon position is not changed)
                    marginStart = (10 * density).toInt()
                }

                val titleView = android.widget.TextView(this@MainActivity).apply {
                    layoutParams = params
                    text = getString(R.string.app_name)
                    textSize = 20f
                    maxLines = 1
                    // Same as Total Commander: cut the title with an ellipsis at the end ("Total Co…")
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(config.textColor)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { goHome() }
                }
                toolbarContainer.addView(titleView)
                toolbarTitleView = titleView
            }
        }
    }

    private fun openSearchField() {
        binding.mainMenu.binding.apply {
            toolbarTitleView?.visibility = android.view.View.GONE
            topToolbarSearchIcon.visibility = android.view.View.VISIBLE
            topToolbarSearch.visibility = android.view.View.VISIBLE
            topToolbarSearch.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(topToolbarSearch, 0)
        }
        binding.mainMenu.requireToolbar().menu.findItem(R.id.search)?.isVisible = false
    }

    private fun showToolbarTitleInsteadOfSearch() {
        binding.mainMenu.binding.apply {
            topToolbarSearchIcon.visibility = android.view.View.GONE
            topToolbarSearch.visibility = android.view.View.GONE
        }
        toolbarTitleView?.visibility = android.view.View.VISIBLE
        binding.mainMenu.requireToolbar().menu.findItem(R.id.search)?.isVisible = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PICKED_PATH, getItemsFragment()?.currentPath ?: "")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val path = savedInstanceState.getString(PICKED_PATH) ?: internalStoragePath

        if (binding.mainViewPager.adapter == null) {
            binding.mainViewPager.onGlobalLayout {
                openPath(path, true)
            }
        } else {
            openPath(path, true)
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
        // MySearchMenu reapplies its theme colors here. Force every layer of the
        // top header, including the icon/title area, to #201E21.
        binding.mainMenu.setBackgroundColor(TOP_BAR_COLOR)
        binding.mainMenu.binding.searchBarContainer.setBackgroundColor(TOP_BAR_COLOR)
        binding.mainMenu.binding.toolbarContainer.setBackgroundColor(TOP_BAR_COLOR)
        binding.mainMenu.binding.toolbarContainer.backgroundTintList =
            android.content.res.ColorStateList.valueOf(TOP_BAR_COLOR)
        binding.mainMenu.requireToolbar().setBackgroundColor(TOP_BAR_COLOR)
    }

    private fun storeStateVariables() {
        config.apply {
            mStoredFontSize = fontSize
            mStoredDateFormat = dateFormat
            mStoredTimeFormat = context.getTimeFormat()
            mStoredShowTabs = showTabs
        }
    }

    private fun tryInitFileManager() {
        val hadPermission = hasStoragePermission()
        handleStoragePermission {
            checkOTGPath()
            if (it) {
                if (binding.mainViewPager.adapter == null) {
                    initFragments()
                }

                binding.mainViewPager.onGlobalLayout {
                    initFileManager(!hadPermission)
                }
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }
    }

    private fun initFileManager(refreshRecents: Boolean) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val data = intent.data
            if (data?.scheme == "file") {
                openPath(data.path!!)
            } else {
                val path = getRealPathFromURI(data!!)
                if (path != null) {
                    openPath(path)
                } else {
                    // Fallback: Total Commander–style home screen
                    openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
                }
            }

            if (!File(data.path!!).isDirectory) {
                tryOpenPathIntent(data.path!!, false, finishActivity = true)
            }

            binding.mainViewPager.currentItem = 0
        } else {
            // Open Total Commander–style home screen on app start
            openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
        }

        if (refreshRecents) {
            getRecentsFragment()?.refreshFragment()
        }
    }

    private fun initFragments() {
        binding.mainViewPager.apply {
            adapter = ViewPagerAdapter(this@MainActivity, mTabsToShow)
            offscreenPageLimit = 2
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {}

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

                override fun onPageSelected(position: Int) {
                    binding.mainTabsHolder.getTabAt(position)?.select()
                    getAllFragments().forEach {
                        (it as? ItemOperationsListener)?.finishActMode()
                    }
                    refreshMenuItems()
                }
            })
            currentItem = config.lastUsedViewPagerPage

            onGlobalLayout {
                refreshMenuItems()
            }
        }
    }

    private fun setupTabs() {
        binding.mainTabsHolder.removeAllTabs()
        val action = intent.action
        val isPickFileIntent = action == RingtoneManager.ACTION_RINGTONE_PICKER
                || action == Intent.ACTION_GET_CONTENT
                || action == Intent.ACTION_PICK
        val isCreateDocumentIntent = action == Intent.ACTION_CREATE_DOCUMENT

        if (isPickFileIntent) {
            mTabsToShow.remove(TAB_STORAGE_ANALYSIS)
            if (mTabsToShow.none { it and config.showTabs != 0 }) {
                config.showTabs = TAB_FILES
                mStoredShowTabs = TAB_FILES
                mTabsToShow = arrayListOf(TAB_FILES)
            }
        } else if (isCreateDocumentIntent) {
            mTabsToShow.clear()
            mTabsToShow = arrayListOf(TAB_FILES)
        }

        mTabsToShow.forEachIndexed { index, value ->
            if (config.showTabs and value != 0) {
                binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
                    customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(index))
                    customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(index)
                    AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                    binding.mainTabsHolder.addTab(this)
                }
            }
        }

        binding.mainTabsHolder.apply {
            onTabSelectionChanged(
                tabUnselectedAction = {
                    updateBottomTabItemColors(it.customView, false, getDeselectedTabDrawableIds()[it.position])
                },
                tabSelectedAction = {
                    binding.mainMenu.closeSearch()
                    binding.mainViewPager.currentItem = it.position
                    updateBottomTabItemColors(it.customView, true, getSelectedTabDrawableIds()[it.position])
                }
            )

            beGoneIf(tabCount == 1)
        }
    }

    private fun setupTabColors() {
        binding.apply {
            val activeView = mainTabsHolder.getTabAt(mainViewPager.currentItem)?.customView
            updateBottomTabItemColors(activeView, true, getSelectedTabDrawableIds()[mainViewPager.currentItem])

            getInactiveTabIndexes(mainViewPager.currentItem).forEach { index ->
                val inactiveView = mainTabsHolder.getTabAt(index)?.customView
                updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
            }

            val bottomBarColor = getBottomNavigationBackgroundColor()
            mainTabsHolder.setBackgroundColor(bottomBarColor)
        }
    }

    private fun getTabIcon(position: Int): Drawable {
        val drawableId = when (position) {
            0 -> R.drawable.ic_folder_vector
            1 -> R.drawable.ic_clock_vector
            else -> R.drawable.ic_storage_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(position: Int): String {
        val stringId = when (position) {
            0 -> R.string.files_tab
            1 -> R.string.recents
            else -> R.string.storage
        }

        return resources.getString(stringId)
    }

    private fun checkOTGPath() {
        ensureBackgroundThread {
            if (!config.wasOTGHandled && hasPermission(PERMISSION_WRITE_STORAGE) && hasOTGConnected() && config.OTGPath.isEmpty()) {
                getStorageDirectories().firstOrNull { it.trimEnd('/') != internalStoragePath && it.trimEnd('/') != sdCardPath }?.apply {
                    config.wasOTGHandled = true
                    config.OTGPath = trimEnd('/')
                }
            }
        }
    }

    private fun openPath(path: String, forceRefresh: Boolean = false) {
        // Virtual home screen path must not be rewritten to internal storage
        if (path == org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
            getItemsFragment()?.openPath(path, forceRefresh)
            return
        }

        var newPath = path
        val file = File(path)
        if (config.OTGPath.isNotEmpty() && config.OTGPath == path.trimEnd('/')) {
            newPath = path
        } else if (file.exists() && !file.isDirectory) {
            newPath = file.parent
        } else if (!file.exists() && !isPathOnOTG(newPath)) {
            newPath = internalStoragePath
        }

        getItemsFragment()?.openPath(newPath, forceRefresh)
    }

    private fun goHome() {
        val current = getCurrentFragment()?.currentPath
        if (current != org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
            openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
        }
    }

    fun openOverflowMenu() {
        binding.mainMenu.requireToolbar().showOverflowMenu()
    }

    fun showSortingDialog() {
        ChangeSortingDialog(this, getCurrentFragment()!!.currentPath) {
            (getCurrentFragment() as? ItemsFragment)?.refreshFragment()
        }
    }

    private fun addFavorite() {
        config.addFavorite(getCurrentFragment()!!.currentPath)
        refreshMenuItems()
    }

    private fun removeFavorite() {
        config.removeFavorite(getCurrentFragment()!!.currentPath)
        refreshMenuItems()
    }

    private fun toggleFilenameVisibility() {
        config.displayFilenames = !config.displayFilenames
        getAllFragments().forEach {
            (it as? ItemOperationsListener)?.toggleFilenameVisibility()
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.fileColumnCnt
        RadioGroupDialog(this, items, config.fileColumnCnt) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.fileColumnCnt = newColumnCount
                getAllFragments().forEach {
                    (it as? ItemOperationsListener)?.columnCountChanged()
                }
            }
        }
    }

    fun updateFragmentColumnCounts() {
        getAllFragments().forEach {
            (it as? ItemOperationsListener)?.columnCountChanged()
        }
    }

    private fun goToFavorite() {
        val favorites = config.favorites
        val items = ArrayList<RadioItem>(favorites.size)
        var currFavoriteIndex = -1

        favorites.forEachIndexed { index, path ->
            val visiblePath = humanizePath(path).replace("/", " / ")
            items.add(RadioItem(index, visiblePath, path))
            if (path == getCurrentFragment()!!.currentPath) {
                currFavoriteIndex = index
            }
        }

        RadioGroupDialog(this, items, currFavoriteIndex, R.string.go_to_favorite) {
            openPath(it.toString())
        }
    }

    private fun setAsHome() {
        config.homeFolder = getCurrentFragment()!!.currentPath
        toast(R.string.home_folder_updated)
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, getCurrentFragment()!!.currentPath, getCurrentFragment() is ItemsFragment) {
            getAllFragments().forEach {
                it?.refreshFragment()
            }
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            handleHiddenFolderPasswordProtection {
                toggleTemporarilyShowHidden(true)
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        config.temporarilyShowHidden = show
        getAllFragments().forEach {
            it?.refreshFragment()
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GESTURE_VIEWS or LICENSE_AUTOFITTEXTVIEW or LICENSE_ZIP4J

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_3_title_commons, R.string.faq_3_text_commons),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        if (resources.getBoolean(R.bool.is_google_play_build)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
            faqItems.add(FAQItem(R.string.faq_7_title_commons, R.string.faq_7_text_commons))
            faqItems.add(FAQItem(R.string.faq_10_title_commons, R.string.faq_10_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun checkIfRootAvailable() {
        ensureBackgroundThread {
            config.isRootAvailable = RootTools.isRootAvailable()
            if (config.isRootAvailable && config.enableRootAccess) {
                RootHelpers(this).askRootIfNeeded {
                    config.enableRootAccess = it
                }
            }
        }
    }

    private fun checkInvalidFavorites() {
        ensureBackgroundThread {
            config.favorites.forEach {
                if (!isPathOnOTG(it) && !isPathOnSD(it) && !File(it).exists()) {
                    config.removeFavorite(it)
                }
            }
        }
    }

    fun pickedPath(path: String) {
        val resultIntent = Intent()
        val uri = getFilePublicUri(File(path), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndType(uri, type)
        resultIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // used at apps that have no file access at all, but need to work with files. For example Simple Calendar uses this at exporting events into a file
    fun createDocumentConfirmed(path: String) {
        val filename = intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""
        if (filename.isEmpty()) {
            InsertFilenameDialog(this, internalStoragePath) { newFilename ->
                finishCreateDocumentIntent(path, newFilename)
            }
        } else {
            finishCreateDocumentIntent(path, filename)
        }
    }

    private fun finishCreateDocumentIntent(path: String, filename: String) {
        val resultIntent = Intent()
        val uri = getFilePublicUri(File(path, filename), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndType(uri, type)
        resultIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    fun pickedRingtone(path: String) {
        val uri = getFilePublicUri(File(path), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        Intent().apply {
            setDataAndType(uri, type)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }

    fun pickedPaths(paths: ArrayList<String>) {
        val newPaths = paths.map { getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData("Attachment", arrayOf(paths.getMimeType()), ClipData.Item(newPaths.removeAt(0)))

        newPaths.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        Intent().apply {
            this.clipData = clipData
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }

    fun openedDirectory() {
        if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
        }
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until binding.mainTabsHolder.tabCount).filter { it != activeIndex }

    private fun getSelectedTabDrawableIds(): ArrayList<Int> {
        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

        if (showTabs and TAB_FILES != 0) {
            icons.add(R.drawable.ic_folder_vector)
        }

        if (showTabs and TAB_RECENT_FILES != 0) {
            icons.add(R.drawable.ic_clock_filled_vector)
        }

        if (showTabs and TAB_STORAGE_ANALYSIS != 0) {
            icons.add(R.drawable.ic_storage_vector)
        }

        return icons
    }

    private fun getDeselectedTabDrawableIds(): ArrayList<Int> {
        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

        if (showTabs and TAB_FILES != 0) {
            icons.add(R.drawable.ic_folder_outline_vector)
        }

        if (showTabs and TAB_RECENT_FILES != 0) {
            icons.add(R.drawable.ic_clock_vector)
        }

        if (showTabs and TAB_STORAGE_ANALYSIS != 0) {
            icons.add(R.drawable.ic_storage_vector)
        }

        return icons
    }

    private fun getRecentsFragment() = findViewById<RecentsFragment>(R.id.recents_fragment)
    private fun getItemsFragment() = findViewById<ItemsFragment>(R.id.items_fragment)
    private fun getStorageFragment() = findViewById<StorageFragment>(R.id.storage_fragment)
    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> =
        arrayListOf(getItemsFragment(), getRecentsFragment(), getStorageFragment())

    private fun getCurrentFragment(): MyViewPagerFragment<*>? {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment<*>>()
        if (showTabs and TAB_FILES != 0) {
            fragments.add(getItemsFragment())
        }

        if (showTabs and TAB_RECENT_FILES != 0) {
            fragments.add(getRecentsFragment())
        }

        if (showTabs and TAB_STORAGE_ANALYSIS != 0) {
            fragments.add(getStorageFragment())
        }

        return fragments.getOrNull(binding.mainViewPager.currentItem)
    }

    private fun getTabsList() = arrayListOf(TAB_FILES, TAB_RECENT_FILES, TAB_STORAGE_ANALYSIS)

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
