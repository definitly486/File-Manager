package org.fossify.filemanager.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Parcelable
import android.graphics.Color
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.StoragePickerDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.Breadcrumbs
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.MainActivity
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.adapters.ItemsAdapter
import org.fossify.filemanager.databinding.ItemsFragmentBinding
import org.fossify.filemanager.dialogs.CreateNewItemDialog
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import java.io.File
import java.util.Locale

class ItemsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.ItemsInnerBinding>(context, attributeSet),
    ItemOperationsListener,
    Breadcrumbs.BreadcrumbsListener {
    private var showHidden = false
    private var lastSearchedText = ""
    private var scrollStates = HashMap<String, Parcelable>()
    private var zoomListener: MyRecyclerView.MyZoomListener? = null

    private var storedItems = ArrayList<ListItem>()
    private var itemsIgnoringSearch = ArrayList<ListItem>()
    private lateinit var binding: ItemsFragmentBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ItemsFragmentBinding.bind(this)
        innerBinding = ItemsInnerBinding(binding)
    }

    override fun setupFragment(activity: SimpleActivity) {
        if (this.activity == null) {
            this.activity = activity
            binding.apply {
                breadcrumbs.listener = this@ItemsFragment
                itemsSwipeRefresh.setOnRefreshListener { refreshFragment() }
                parentDirHolder.setOnClickListener { goToParentFolder() }
                homeButton.setOnClickListener { goToHomeFolder() }
                refreshButton.setOnClickListener { refreshFragment() }
                setupBottomBar()
                setupSideButtons()
                itemsFab.setOnClickListener {
                    if (isCreateDocumentIntent) {
                        (activity as MainActivity).createDocumentConfirmed(currentPath)
                    } else {
                        createNewItem()
                    }
                }
            }
        }
    }

    override fun onResume(textColor: Int) {
        context!!.updateTextColors(this)
        getRecyclerAdapter()?.apply {
            updatePrimaryColor()
            updateTextColor(textColor)
            initDrawables()
        }

        updateBarColors(textColor)

        binding.apply {
            val properPrimaryColor = context!!.getProperPrimaryColor()
            itemsFastscroller.updateColors(properPrimaryColor)
            progressBar.setIndicatorColor(properPrimaryColor)
            progressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)

            if (currentPath != "") {
                breadcrumbs.updateColor(textColor)
            }

            itemsSwipeRefresh.isEnabled = lastSearchedText.isEmpty() && activity?.config?.enablePullToRefresh != false
        }
    }

    override fun setupFontSize() {
        getRecyclerAdapter()?.updateFontSizes()
        if (currentPath != "") {
            binding.breadcrumbs.updateFontSize(context!!.getTextSize(), false)
        }
    }

    override fun setupDateTimeFormat() {
        getRecyclerAdapter()?.updateDateTimeFormat()
    }

    override fun finishActMode() {
        getRecyclerAdapter()?.finishActMode()
    }

    fun openPath(path: String, forceRefresh: Boolean = false) {
        if ((activity as? BaseSimpleActivity)?.isAskingPermissions == true) {
            return
        }

        // Virtual home screen must keep its exact path (://home)
        val realPath = if (path == org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
            path
        } else {
            var p = path.trimEnd('/')
            if (p.isEmpty()) p = "/"
            p
        }

        scrollStates[currentPath] = getScrollState()!!
        currentPath = realPath
        showHidden = context!!.config.shouldShowHidden()
        showProgressBar()
        getItems(currentPath) { originalPath, listItems ->
            if (currentPath != originalPath) {
                return@getItems
            }

            // Do not re-sort the fixed home screen order
            if (currentPath != org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
                FileDirItem.sorting = context!!.config.getFolderSorting(currentPath)
                listItems.sort()

                if (context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_GRID && listItems.none { it.isSectionTitle }) {
                    if (listItems.any { it.mIsDirectory } && listItems.any { !it.mIsDirectory }) {
                        val firstFileIndex = listItems.indexOfFirst { !it.mIsDirectory }
                        if (firstFileIndex != -1) {
                            val sectionTitle = ListItem("", "", false, 0, 0, 0, false, true)
                            listItems.add(firstFileIndex, sectionTitle)
                        }
                    }
                }
            }

            itemsIgnoringSearch = listItems
            activity?.runOnUiThread {
                (activity as? MainActivity)?.refreshMenuItems()
                addItems(listItems, forceRefresh)
                if (context != null && currentViewType != context!!.config.getFolderViewType(currentPath)) {
                    setupLayoutManager()
                }
                hideProgressBar()
            }
        }
    }

    private fun addItems(items: ArrayList<ListItem>, forceRefresh: Boolean = false) {
        activity?.runOnUiThread {
            binding.itemsSwipeRefresh.isRefreshing = false
            val isHome = currentPath == org.fossify.filemanager.helpers.HOME_SCREEN_PATH
            if (isHome) {
                // Home page: no header actions (home icon hidden here only)
                binding.pathText.text = ""
                binding.freeSpaceText.text = ""
                binding.itemsHeaderActions.beGone()
                binding.itemsHeaderDivider.beGone()
            } else {
                // Folder pages: show parent / free space / home / refresh
                binding.breadcrumbs.setBreadcrumb(currentPath)
                binding.pathText.text = currentPath
                binding.freeSpaceText.text = getFreeSpaceText(currentPath)
                binding.itemsHeaderActions.beVisible()
                binding.itemsHeaderDivider.beVisible()
                binding.parentDirHolder.beVisible()
                binding.homeButton.beVisible()
                binding.refreshButton.beVisible()
            }
            if (!forceRefresh && items.hashCode() == storedItems.hashCode()) {
                return@runOnUiThread
            }

            storedItems = items
            if (binding.itemsList.adapter == null) {
                binding.breadcrumbs.updateFontSize(context!!.getTextSize(), true)
            }

            ItemsAdapter(activity as SimpleActivity, storedItems, this, binding.itemsList, isPickMultipleIntent, binding.itemsSwipeRefresh) {
                if ((it as? ListItem)?.isSectionTitle == true) {
                    openDirectory(it.mPath)
                    searchClosed()
                } else {
                    itemClicked(it as FileDirItem)
                }
            }.apply {
                setupZoomListener(zoomListener)
                selectionListener = { selected, total ->
                    binding.selectionCount.text = "$selected/$total"
                }
                binding.selectionCount.text = "0/${getSelectableItemCount()}"
                binding.itemsList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.itemsList.scheduleLayoutAnimation()
            }

            getRecyclerLayoutManager().onRestoreInstanceState(scrollStates[currentPath])
        }
    }

    private fun getScrollState() = getRecyclerLayoutManager().onSaveInstanceState()

    private fun getRecyclerLayoutManager() = (binding.itemsList.layoutManager as MyGridLayoutManager)

    @SuppressLint("NewApi")
    private fun getItems(path: String, callback: (originalPath: String, items: ArrayList<ListItem>) -> Unit) {
        if (path == org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
            ensureBackgroundThread {
                callback(path, buildHomeScreenItems())
            }
            return
        }

        ensureBackgroundThread {
            if (activity?.isDestroyed == false && activity?.isFinishing == false) {
                val config = context!!.config
                if (context.isRestrictedSAFOnlyRoot(path)) {
                    activity?.runOnUiThread { hideProgressBar() }
                    activity?.handleAndroidSAFDialog(path, openInSystemAppAllowed = true) {
                        if (!it) {
                            activity?.toast(R.string.no_storage_permissions)
                            return@handleAndroidSAFDialog
                        }
                        val getProperChildCount = context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_LIST
                        context.getAndroidSAFFileItems(path, context.config.shouldShowHidden(), getProperChildCount) { fileItems ->
                            callback(path, getListItemsFromFileDirItems(fileItems))
                        }
                    }
                } else if (context!!.isPathOnOTG(path) && config.OTGTreeUri.isNotEmpty()) {
                    val getProperFileSize = context!!.config.getFolderSorting(currentPath) and SORT_BY_SIZE != 0
                    context!!.getOTGItems(path, config.shouldShowHidden(), getProperFileSize) {
                        callback(path, getListItemsFromFileDirItems(it))
                    }
                } else if (!config.enableRootAccess || !context!!.isPathOnRoot(path)) {
                    getRegularItemsOf(path, callback)
                } else {
                    RootHelpers(activity!!).getFiles(path, callback)
                }
            }
        }
    }

    private fun getRegularItemsOf(path: String, callback: (originalPath: String, items: ArrayList<ListItem>) -> Unit) {
        val items = ArrayList<ListItem>()
        val files = File(path).listFiles()?.filterNotNull()
        if (context == null || files == null) {
            callback(path, items)
            return
        }

        val isSortingBySize = context!!.config.getFolderSorting(currentPath) and SORT_BY_SIZE != 0
        val getProperChildCount = context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_LIST
        val lastModifieds = context!!.getFolderLastModifieds(path)

        for (file in files) {
            val listItem = getListItemFromFile(file, isSortingBySize, lastModifieds, false)
            if (listItem != null) {
                if (wantedMimeTypes.any { isProperMimeType(it, file.absolutePath, file.isDirectory) }) {
                    items.add(listItem)
                }
            }
        }

        // send out the initial item list asap, get proper child count asynchronously as it can be slow
        callback(path, items)

        if (getProperChildCount) {
            items.filter { it.mIsDirectory }.forEach {
                if (context != null) {
                    val childrenCount = it.getDirectChildrenCount(activity as BaseSimpleActivity, showHidden)
                    if (childrenCount != 0) {
                        activity?.runOnUiThread {
                            getRecyclerAdapter()?.updateChildCount(it.mPath, childrenCount)
                        }
                    }
                }
            }
        }
    }

    private fun getListItemFromFile(file: File, isSortingBySize: Boolean, lastModifieds: HashMap<String, Long>, getProperChildCount: Boolean): ListItem? {
        val curPath = file.absolutePath
        val curName = file.name
        if (!showHidden && curName.startsWith(".")) {
            return null
        }

        var lastModified = lastModifieds.remove(curPath)
        val isDirectory = file.isDirectory
        val children = if (isDirectory && getProperChildCount) file.getDirectChildrenCount(context, showHidden) else 0
        val size = if (isDirectory) {
            if (isSortingBySize) {
                file.getProperSize(showHidden)
            } else {
                0L
            }
        } else {
            file.length()
        }

        if (lastModified == null) {
            lastModified = file.lastModified()
        }

        return ListItem(curPath, curName, isDirectory, children, size, lastModified, false, false)
    }

    private fun getListItemsFromFileDirItems(fileDirItems: ArrayList<FileDirItem>): ArrayList<ListItem> {
        val listItems = ArrayList<ListItem>()
        fileDirItems.forEach {
            val listItem = ListItem(it.path, it.name, it.isDirectory, it.children, it.size, it.modified, false, false)
            if (wantedMimeTypes.any { mimeType -> isProperMimeType(mimeType, it.path, it.isDirectory) }) {
                listItems.add(listItem)
            }
        }
        return listItems
    }

    private fun itemClicked(item: FileDirItem) {
        when (item.path) {
            "://internal" -> openDirectory(context!!.internalStoragePath)
            "://user_location" -> {
                StoragePickerDialog(activity as SimpleActivity, currentPath, context!!.config.enableRootAccess, true) {
                    openPath(it)
                }
            }
            "://bookmarks" -> {
                activity?.startActivity(
                    android.content.Intent(activity, org.fossify.filemanager.activities.FavoritesActivity::class.java)
                )
            }
            else -> {
                if (item.isDirectory) {
                    openDirectory(item.path)
                } else {
                    clickedPath(item.path)
                }
            }
        }
    }

    private fun openDirectory(path: String) {
        (activity as? MainActivity)?.apply {
            openedDirectory()
        }
        openPath(path)
    }

    override fun searchQueryChanged(text: String) {
        lastSearchedText = text
        if (context == null) {
            return
        }

        binding.apply {
            itemsSwipeRefresh.isEnabled = text.isEmpty() && activity?.config?.enablePullToRefresh != false
            when {
                text.isEmpty() -> {
                    itemsFastscroller.beVisible()
                    getRecyclerAdapter()?.updateItems(itemsIgnoringSearch)
                    itemsPlaceholder.beGone()
                    itemsPlaceholder2.beGone()
                    hideProgressBar()
                }

                text.length == 1 -> {
                    itemsFastscroller.beGone()
                    itemsPlaceholder.beVisible()
                    itemsPlaceholder2.beVisible()
                    hideProgressBar()
                }

                else -> {
                    showProgressBar()
                    ensureBackgroundThread {
                        val files = searchFiles(text, currentPath)
                        files.sortBy { it.getParentPath() }

                        if (lastSearchedText != text) {
                            return@ensureBackgroundThread
                        }

                        val listItems = ArrayList<ListItem>()

                        var previousParent = ""
                        files.forEach {
                            val parent = it.mPath.getParentPath()
                            if (!it.isDirectory && parent != previousParent && context != null) {
                                val sectionTitle = ListItem(parent, context!!.humanizePath(parent), false, 0, 0, 0, true, false)
                                listItems.add(sectionTitle)
                                previousParent = parent
                            }

                            if (it.isDirectory) {
                                val sectionTitle = ListItem(it.path, context!!.humanizePath(it.path), true, 0, 0, 0, true, false)
                                listItems.add(sectionTitle)
                                previousParent = parent
                            }

                            if (!it.isDirectory) {
                                listItems.add(it)
                            }
                        }

                        activity?.runOnUiThread {
                            getRecyclerAdapter()?.updateItems(listItems, text)
                            itemsFastscroller.beVisibleIf(listItems.isNotEmpty())
                            itemsPlaceholder.beVisibleIf(listItems.isEmpty())
                            itemsPlaceholder2.beGone()
                            hideProgressBar()
                        }
                    }
                }
            }
        }
    }

    private fun searchFiles(text: String, path: String): ArrayList<ListItem> {
        val files = ArrayList<ListItem>()
        if (context == null) {
            return files
        }

        val normalizedText = text.normalizeString()
        val sorting = context!!.config.getFolderSorting(path)
        FileDirItem.sorting = context!!.config.getFolderSorting(currentPath)
        val isSortingBySize = sorting and SORT_BY_SIZE != 0
        File(path).listFiles()?.sortedBy { it.isDirectory }?.forEach {
            if (!showHidden && it.isHidden) {
                return@forEach
            }

            if (it.isDirectory) {
                if (it.name.normalizeString().contains(normalizedText, true)) {
                    val fileDirItem = getListItemFromFile(it, isSortingBySize, HashMap(), false)
                    if (fileDirItem != null) {
                        files.add(fileDirItem)
                    }
                }

                files.addAll(searchFiles(text, it.absolutePath))
            } else {
                if (it.name.normalizeString().contains(normalizedText, true)) {
                    val fileDirItem = getListItemFromFile(it, isSortingBySize, HashMap(), false)
                    if (fileDirItem != null) {
                        files.add(fileDirItem)
                    }
                }
            }
        }
        return files
    }

    private fun searchClosed() {
        binding.apply {
            lastSearchedText = ""
            itemsSwipeRefresh.isEnabled = activity?.config?.enablePullToRefresh != false
            itemsFastscroller.beVisible()
            itemsPlaceholder.beGone()
            itemsPlaceholder2.beGone()
            hideProgressBar()
        }
    }

    private fun setupBottomBar() {
        binding.apply {
            barMenu.setOnClickListener { (activity as? MainActivity)?.openOverflowMenu() }
            barNewFile.setOnClickListener { createNewItem(false) }
            barNewFolder.setOnClickListener { createNewItem(true) }
            barDelete.setOnClickListener { withSelection { performAction(R.id.cab_delete) } }
            barSort.setOnClickListener { (activity as? MainActivity)?.showSortingDialog() }
            // swap: select everything that is not selected and deselect what is selected
            barSwap.setOnClickListener { getRecyclerAdapter()?.invertSelection() }
        }
    }

    private fun setupSideButtons() {
        binding.apply {
            sideCopy.setOnClickListener { withSelection { performAction(R.id.cab_copy_to) } }
            sideMove.setOnClickListener { withSelection { performAction(R.id.cab_move_to) } }
            sideOpen.setOnClickListener {
                val dir = getRecyclerAdapter()?.getSingleSelectedDirectory()
                if (dir != null) {
                    getRecyclerAdapter()?.finishActMode()
                    openDirectory(dir)
                } else {
                    activity?.toast(R.string.select_items_first)
                }
            }
            sideSelectAll.setOnClickListener { getRecyclerAdapter()?.selectAllItems() }
            sideInvert.setOnClickListener { getRecyclerAdapter()?.invertSelection() }
        }
    }

    private fun withSelection(action: ItemsAdapter.() -> Unit) {
        val adapter = getRecyclerAdapter()
        if (adapter != null && adapter.hasSelection()) {
            adapter.action()
        } else {
            activity?.toast(R.string.select_items_first)
        }
    }

    private fun updateBarColors(textColor: Int) {
        binding.apply {
            itemsBottomHolder.setBackgroundColor(context!!.getProperBackgroundColor())
            itemsFab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#312f32"))
            listOf(barMenu, barNewFile, barNewFolder, barDelete, barSort, barSwap, sideCopy, sideMove, sideOpen, sideSelectAll, sideInvert)
                .forEach { it.setColorFilter(textColor) }
        }
    }

    private fun createNewItem(isDirectory: Boolean? = null) {
        CreateNewItemDialog(activity as SimpleActivity, currentPath, isDirectory) {
            if (it) {
                refreshFragment()
            } else {
                activity?.toast(R.string.unknown_error_occurred)
            }
        }
    }

    private fun getRecyclerAdapter() = binding.itemsList.adapter as? ItemsAdapter

    private fun setupLayoutManager() {
        if (context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_GRID) {
            currentViewType = VIEW_TYPE_GRID
            setupGridLayoutManager()
        } else {
            currentViewType = VIEW_TYPE_LIST
            setupListLayoutManager()
        }

        binding.itemsList.adapter = null
        initZoomListener()
        addItems(storedItems, true)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.itemsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context?.config?.fileColumnCnt ?: 3

        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (getRecyclerAdapter()?.isASectionTitle(position) == true || getRecyclerAdapter()?.isGridTypeDivider(position) == true) {
                    layoutManager.spanCount
                } else {
                    1
                }
            }
        }
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.itemsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        zoomListener = null
    }

    private fun initZoomListener() {
        if (context?.config?.getFolderViewType(currentPath) == VIEW_TYPE_GRID) {
            val layoutManager = binding.itemsList.layoutManager as MyGridLayoutManager
            zoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            zoomListener = null
        }
    }

    private fun increaseColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            context!!.config.fileColumnCnt += 1
            (activity as? MainActivity)?.updateFragmentColumnCounts()
        }
    }

    private fun reduceColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            context!!.config.fileColumnCnt -= 1
            (activity as? MainActivity)?.updateFragmentColumnCounts()
        }
    }

    override fun columnCountChanged() {
        (binding.itemsList.layoutManager as MyGridLayoutManager).spanCount = context!!.config.fileColumnCnt
        (activity as? MainActivity)?.refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, listItems.size)
        }
    }

    fun showProgressBar() {
        binding.progressBar.show()
    }

    private fun hideProgressBar() {
        binding.progressBar.hide()
    }

    fun getBreadcrumbs() = binding.breadcrumbs

    private fun goToParentFolder() {
        getRecyclerAdapter()?.finishActMode()
        val path = currentPath.trimEnd('/')
        val ctx = context ?: return
        // Volume roots (internal storage, SD, OTG, device root) → TC home screen
        val storageRoots = mutableListOf(
            ctx.internalStoragePath.trimEnd('/'),
            "/"
        )
        val sd = ctx.sdCardPath.trimEnd('/')
        if (sd.isNotEmpty()) storageRoots.add(sd)
        val otg = ctx.config.OTGPath.trimEnd('/')
        if (otg.isNotEmpty()) storageRoots.add(otg)
        if (path in storageRoots || path.isEmpty()) {
            openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
            return
        }
        val crumbs = binding.breadcrumbs
        val count = crumbs.getItemCount()
        if (count > 1) {
            openPath(crumbs.getItem(count - 2).path)
        } else {
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isEmpty() || parent == path) {
                openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
            } else {
                openPath(parent)
            }
        }
    }

    private fun goToHomeFolder() {
        if (currentPath != org.fossify.filemanager.helpers.HOME_SCREEN_PATH) {
            getRecyclerAdapter()?.finishActMode()
            openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
        }
    }

    /**
     * Builds the Total Commander–style home list:
     * Internal storage, user location, Photos, Downloads, Root, Bookmarks, My apps.
     */
    private fun buildHomeScreenItems(): ArrayList<ListItem> {
        val ctx = context ?: return ArrayList()
        val items = ArrayList<ListItem>()
        val internal = ctx.internalStoragePath

        // 1. Internal shared storage — free/total encoded in size/modified for the home row UI
        val internalFile = File(internal)
        val freeSpace = internalFile.usableSpace.coerceAtLeast(0)
        val totalSpace = internalFile.totalSpace.coerceAtLeast(0)
        // mChildren = -1 marks home-screen rows (no DIR / no date-time in the list)
        items.add(
            ListItem(
                mPath = "://internal",
                mName = ctx.getString(R.string.internal_shared_storage),
                mIsDirectory = true,
                mChildren = -1,
                mSize = freeSpace,
                mModified = totalSpace,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        // 2. User-defined location (opens storage picker; green refresh shown in adapter)
        items.add(
            ListItem(
                mPath = "://user_location",
                mName = ctx.getString(R.string.user_defined_location),
                mIsDirectory = true,
                mChildren = -1,
                mSize = 0L,
                mModified = 0L,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        // 3. Photos (DCIM)
        val photosPath = "$internal/DCIM"
        items.add(
            ListItem(
                mPath = photosPath,
                mName = ctx.getString(R.string.photos),
                mIsDirectory = true,
                mChildren = -1,
                mSize = 0L,
                mModified = 0L,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        // 4. Downloads
        val downloadsPath = "$internal/Download"
        items.add(
            ListItem(
                mPath = downloadsPath,
                mName = ctx.getString(R.string.downloads),
                mIsDirectory = true,
                mChildren = -1,
                mSize = 0L,
                mModified = 0L,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        // 5. Root folder
        items.add(
            ListItem(
                mPath = "/",
                mName = ctx.getString(R.string.root_folder),
                mIsDirectory = true,
                mChildren = -1,
                mSize = 0L,
                mModified = 0L,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        // 6. Bookmarks (favorites)
        items.add(
            ListItem(
                mPath = "://bookmarks",
                mName = ctx.getString(R.string.bookmarks),
                mIsDirectory = true,
                mChildren = -1,
                mSize = 0L,
                mModified = 0L,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        // 7. My apps
        val appsPath = "$internal/Android/data"
        items.add(
            ListItem(
                mPath = appsPath,
                mName = ctx.getString(R.string.my_apps),
                mIsDirectory = true,
                mChildren = -1,
                mSize = 0L,
                mModified = 0L,
                isSectionTitle = false,
                isGridTypeDivider = false
            )
        )

        return items
    }

    private fun getFreeSpaceText(path: String): String {
        val file = File(path)
        val total = file.totalSpace
        if (total <= 0) {
            return ""
        }

        return "${formatGigabytes(file.usableSpace)} / ${formatGigabytes(total)}"
    }

    private fun formatGigabytes(bytes: Long) = String.format(Locale.US, "%.1fG", bytes / 1073741824.0)

    override fun toggleFilenameVisibility() {
        getRecyclerAdapter()?.updateDisplayFilenamesInGrid()
    }

    override fun breadcrumbClicked(id: Int) {
        if (id == 0) {
            // Root breadcrumb → TC home screen
            getRecyclerAdapter()?.finishActMode()
            openPath(org.fossify.filemanager.helpers.HOME_SCREEN_PATH)
        } else {
            val item = binding.breadcrumbs.getItem(id)
            openPath(item.path)
        }
    }

    override fun refreshFragment() {
        openPath(currentPath)
    }

    override fun deleteFiles(files: ArrayList<FileDirItem>) {
        val hasFolder = files.any { it.isDirectory }
        handleFileDeleting(files, hasFolder)
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        (activity as MainActivity).pickedPaths(paths)
    }
}
