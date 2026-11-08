package com.coke.nestspace.ui.desktop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.VirtualApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopScreen(
    virtualApps: List<VirtualApp>,
    systemApps: List<com.coke.nestspace.data.AppInfo>,
    wallpaper: ImageBitmap?,
    onLaunchVirtual: (String, String?) -> Unit,
    onLaunchSystem: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAppManager: () -> Unit,
    onLaunchShortcut: (String, AppShortcut) -> Unit,
    loadShortcuts: suspend (String) -> List<AppShortcut>,
    onOpenRunning: () -> Unit,
    onUninstallVirtual: (String) -> Unit,
    onRemoveSystemApp: (String) -> Unit
) {
    val context = LocalContext.current
    val languageRevision = AppLanguage.revision
    val dateLabel = remember(languageRevision) {
        val chinese = AppLanguage.isChinese()
        val pattern = if (chinese) "M月d日 EEEE" else "EEEE, MMM d"
        SimpleDateFormat(pattern, if (chinese) Locale.CHINA else Locale.getDefault()).format(Date())
    }
    val entries = remember(virtualApps, systemApps, languageRevision) {
        buildEntries(virtualApps, systemApps)
    }
    val appEntries = remember(entries) {
        entries.filter { it.kind == LauncherKind.VIRTUAL || it.kind == LauncherKind.SYSTEM }
    }
    val dockEntries = remember(entries) {
        entries.filter { it.kind != LauncherKind.VIRTUAL && it.kind != LauncherKind.SYSTEM }
    }
    val entriesById = remember(appEntries) { appEntries.associateBy { it.id } }
    var savedLayout by remember { mutableStateOf(DesktopLayoutStore.load(context)) }
    // Folders created empty keep rendering while the user adds their first
    // app. The allowance is intentionally in-memory: after a restart a folder
    // with only one app goes back to the normal auto-dissolve behavior.
    var fillingFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    val slots = remember(savedLayout, appEntries, fillingFolders) {
        resolveSlots(savedLayout, appEntries, fillingFolders)
    }
    val folders = slots.filterIsInstance<DesktopSlot.Folder>()

    fun commit(next: List<DesktopSlot>) {
        val saved = next.map { it.toSaved() }
        savedLayout = saved
        DesktopLayoutStore.save(context, saved)
    }

    fun launch(entry: LauncherEntry) {
        when (entry.kind) {
            LauncherKind.VIRTUAL -> onLaunchVirtual(entry.packageName, entry.instanceId)
            LauncherKind.SYSTEM -> onLaunchSystem(entry.packageName)
            LauncherKind.BROWSER -> onOpenBrowser()
            LauncherKind.AGENT -> onOpenAgent()
            LauncherKind.SPACES -> onOpenSpaces()
            LauncherKind.RUNNING -> onOpenRunning()
        }
    }

    var menuSlot by remember { mutableStateOf<DesktopSlot?>(null) }
    var menuItemBounds by remember { mutableStateOf(Rect.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var blankMenuPoint by remember { mutableStateOf<Offset?>(null) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var addingToFolderId by remember { mutableStateOf<String?>(null) }
    var folderPickerApp by remember { mutableStateOf<LauncherEntry?>(null) }
    var renamingFolder by remember { mutableStateOf<DesktopSlot.Folder?>(null) }
    var renameText by remember { mutableStateOf("") }
    var folderAppMenu by remember { mutableStateOf<LauncherEntry?>(null) }
    var confirmUninstall by remember { mutableStateOf<LauncherEntry?>(null) }
    var confirmRemove by remember { mutableStateOf<LauncherEntry?>(null) }
    var appInfo by remember { mutableStateOf<LauncherEntry?>(null) }
    var menuShortcuts by remember { mutableStateOf<List<AppShortcut>>(emptyList()) }

    fun createEmptyFolder() {
        val name = AppLanguage.tr("文件夹", "Folder")
        val folder = DesktopSlot.Folder(
            id = "folder:${System.currentTimeMillis()}",
            name = name,
            apps = emptyList()
        )
        commit(slots + folder)
        renamingFolder = folder
        renameText = name
    }

    fun addToFolder(appId: String, folderId: String) {
        val app = entriesById[appId] ?: return
        val next = ArrayList<DesktopSlot>()
        var found = false
        var targetWasEmpty = false
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> if (slot.entry.id != appId) next += slot
                is DesktopSlot.Folder -> {
                    val remaining = slot.apps.filterNot { it.id == appId }
                    if (slot.id == folderId) {
                        targetWasEmpty = slot.apps.isEmpty()
                        next += slot.copy(apps = remaining + app)
                        found = true
                    } else if (remaining.size == 1) {
                        next += DesktopSlot.App(remaining.first())
                    } else if (remaining.isNotEmpty()) {
                        next += slot.copy(apps = remaining)
                    }
                }
            }
        }
        if (found) {
            fillingFolders = if (targetWasEmpty) {
                fillingFolders + folderId
            } else {
                fillingFolders - folderId
            }
            commit(next)
        }
    }

    fun removeFromFolder(appId: String) {
        val app = entriesById[appId] ?: return
        val next = ArrayList<DesktopSlot>()
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> next += slot
                is DesktopSlot.Folder -> {
                    val remaining = slot.apps.filterNot { it.id == appId }
                    if (remaining.size == 1) {
                        next += DesktopSlot.App(remaining.first())
                    } else if (remaining.isNotEmpty()) {
                        next += slot.copy(apps = remaining)
                    }
                }
            }
        }
        next += DesktopSlot.App(app)
        commit(next)
    }

    fun addAppToTopLevelEnd(appId: String, front: Boolean) {
        val app = entriesById[appId] ?: return
        val next = ArrayList<DesktopSlot>()
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> if (slot.entry.id != appId) next += slot
                is DesktopSlot.Folder -> {
                    val remaining = slot.apps.filterNot { it.id == appId }
                    if (remaining.size == 1) {
                        next += DesktopSlot.App(remaining.first())
                    } else if (remaining.isNotEmpty()) {
                        next += slot.copy(apps = remaining)
                    }
                }
            }
        }
        val appSlot = DesktopSlot.App(app)
        if (front) next.add(0, appSlot) else next.add(appSlot)
        commit(next)
    }

    fun renameFolder(folderId: String, name: String) {
        if (name.isBlank()) return
        commit(
            slots.map {
                if (it is DesktopSlot.Folder && it.id == folderId) {
                    it.copy(name = name)
                } else {
                    it
                }
            }
        )
    }

    fun dissolveFolder(folderId: String) {
        val next = ArrayList<DesktopSlot>()
        slots.forEach { slot ->
            if (slot is DesktopSlot.Folder && slot.id == folderId) {
                slot.apps.forEach { next += DesktopSlot.App(it) }
            } else {
                next += slot
            }
        }
        commit(next)
        fillingFolders = fillingFolders - folderId
        if (openFolderId == folderId) openFolderId = null
    }

    fun removeApp(appId: String) {
        commit(
            slots.mapNotNull { slot ->
                when (slot) {
                    is DesktopSlot.App -> if (slot.entry.id == appId) null else slot
                    is DesktopSlot.Folder -> {
                        val remaining = slot.apps.filterNot { it.id == appId }
                        when {
                            remaining.isEmpty() -> null
                            remaining.size == 1 -> DesktopSlot.App(remaining.first())
                            else -> slot.copy(apps = remaining)
                        }
                    }
                }
            }
        )
    }

    val gridState = rememberLazyGridState()
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var folderTargetIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val gridColumns = when {
        configuration.screenWidthDp >= 1_000 -> 8
        configuration.screenWidthDp >= 760 -> 7
        configuration.screenWidthDp >= 480 -> 5
        else -> 4
    }
    val tabletLayout = configuration.smallestScreenWidthDp >= 480
    val haptic = LocalHapticFeedback.current
    val dragThreshold = with(density) { 8.dp.toPx() }
    // Deliberate product rule: a small overlap creates a folder, while a
    // larger overlap (30%+ including full overlap) switches to reorder/shift.
    val folderMinOverlap = 0.02f
    val folderMaxOverlap = 0.30f

    fun showMenuFor(index: Int, bounds: Rect) {
        val slot = slots.getOrNull(index) ?: return
        menuSize = IntSize.Zero
        menuItemBounds = if (bounds == Rect.Zero) {
            Rect(40f, 180f, 280f, 300f)
        } else {
            bounds
        }
        blankMenuPoint = null
        menuSlot = slot
    }

    fun resetDrag() {
        dragIndex = -1
        dragTargetIndex = -1
        folderTargetIndex = -1
        dragOffset = Offset.Zero
        dragDistance = 0f
    }

    fun combineAppsIntoFolder(draggedId: String, targetId: String) {
        val dragged = entriesById[draggedId] ?: return
        val target = entriesById[targetId] ?: return
        if (dragged.id == target.id) return
        val folderId = "folder:${System.currentTimeMillis()}"
        val name = AppLanguage.tr("文件夹", "Folder")
        val next = ArrayList<DesktopSlot>()
        var inserted = false
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> when (slot.entry.id) {
                    draggedId -> Unit
                    targetId -> {
                        next += DesktopSlot.Folder(folderId, name, listOf(target, dragged))
                        inserted = true
                    }
                    else -> next += slot
                }
                is DesktopSlot.Folder -> next += slot
            }
        }
        if (!inserted) {
            next += DesktopSlot.Folder(folderId, name, listOf(target, dragged))
        }
        commit(next)
    }

    fun handleDragEnd() {
        val from = dragIndex
        val to = dragTargetIndex
        val folderTarget = folderTargetIndex
        val currentSlots = slots
        if (from in currentSlots.indices) {
            val moved = dragDistance > dragThreshold || (to in currentSlots.indices && to != from)
            val folderSlot = currentSlots.getOrNull(folderTarget)
            val moving = currentSlots.getOrNull(from)
            if (moved && moving is DesktopSlot.App && folderSlot is DesktopSlot.App) {
                // Dropping with enough overlap creates a folder instead of
                // merely reordering the grid.
                combineAppsIntoFolder(moving.entry.id, folderSlot.entry.id)
            } else if (moved && to in currentSlots.indices) {
                val target = currentSlots[to]
                if (target is DesktopSlot.Folder && moving is DesktopSlot.App) {
                    addToFolder(moving.entry.id, target.id)
                } else if (from != to) {
                    commit(moveSlot(currentSlots, from, to))
                }
            }
        }
        resetDrag()
    }

    val overlayOpen = menuSlot != null || blankMenuPoint != null || openFolderId != null ||
        addingToFolderId != null || folderPickerApp != null ||
        renamingFolder != null || folderAppMenu != null ||
        confirmUninstall != null || confirmRemove != null || appInfo != null
    LaunchedEffect(overlayOpen) {
        FloatingDotOverlay.setOverlayHidden(context, "desktop-overlay", overlayOpen)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    FloatingDotOverlay.setContainerDesktopVisible(context, true)
                Lifecycle.Event.ON_PAUSE ->
                    FloatingDotOverlay.setContainerDesktopVisible(context, false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            FloatingDotOverlay.setContainerDesktopVisible(context, true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FloatingDotOverlay.setContainerDesktopVisible(context, false)
            FloatingDotOverlay.setOverlayHidden(context, "desktop-overlay", false)
        }
    }

    LaunchedEffect(openFolderId, slots) {
        if (openFolderId != null && slots.none {
                it is DesktopSlot.Folder && it.id == openFolderId
            }
        ) {
            openFolderId = null
        }
    }
    LaunchedEffect(addingToFolderId, slots) {
        if (addingToFolderId != null && slots.none {
                it is DesktopSlot.Folder && it.id == addingToFolderId
            }
        ) {
            addingToFolderId = null
        }
    }

    BackHandler(enabled = menuSlot != null || blankMenuPoint != null) {
        menuSlot = null
        blankMenuPoint = null
    }

    LaunchedEffect(menuSlot?.id) {
        val slot = menuSlot
        menuShortcuts = emptyList()
        if (slot is DesktopSlot.App && slot.entry.kind == LauncherKind.VIRTUAL) {
            val loaded = runCatching { loadShortcuts(slot.entry.packageName) }
                .getOrDefault(emptyList())
            if (menuSlot?.id == slot.id) menuShortcuts = loaded
        }
    }

    val openFolder = openFolderId?.let { id ->
        slots.filterIsInstance<DesktopSlot.Folder>().firstOrNull { it.id == id }
    }
    val addingFolder = addingToFolderId?.let { id ->
        slots.filterIsInstance<DesktopSlot.Folder>().firstOrNull { it.id == id }
    }

    val menuWidthPx = with(density) { 260.dp.roundToPx() }
    val menuGap = with(density) { 6.dp.roundToPx() }
    val menuMargin = with(density) { 12.dp.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val menuWidth = if (menuSize.width > 0) menuSize.width else menuWidthPx
    val menuPosition = if (menuItemBounds == Rect.Zero) {
        IntOffset(menuMargin, menuMargin)
    } else {
        val x = (menuItemBounds.center.x - menuWidth / 2f).roundToInt()
            .coerceIn(
                menuMargin,
                (screenWidthPx - menuWidth - menuMargin).coerceAtLeast(menuMargin)
            )
        val below = menuItemBounds.bottom.roundToInt() + menuGap
        val above = menuItemBounds.top.roundToInt() - menuSize.height - menuGap
        val y = when {
            menuSize.height > 0 && below + menuSize.height <= screenHeightPx - menuMargin ->
                below
            menuSize.height > 0 && above >= menuMargin -> above
            else -> below
        }.coerceIn(
            menuMargin,
            (screenHeightPx - menuSize.height - menuMargin).coerceAtLeast(menuMargin)
        )
        IntOffset(x, y)
    }

    val previewSlots = if (folderTargetIndex < 0 &&
        dragIndex in slots.indices &&
        dragTargetIndex in slots.indices &&
        dragIndex != dragTargetIndex
    ) {
        moveSlot(slots, dragIndex, dragTargetIndex)
    } else {
        null
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(gridState) {
                val dockHeight = with(density) { 112.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val start = down.position
                    val finished = withTimeoutOrNull(
                        viewConfiguration.longPressTimeoutMillis
                    ) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            if ((change.position - start).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                break
                            }
                        }
                        true
                    }
                    if (finished == null) {
                        val onApp = gridState.layoutInfo.visibleItemsInfo.any { info ->
                            start.x >= info.offset.x &&
                                start.x < info.offset.x + info.size.width &&
                                start.y >= info.offset.y &&
                                start.y < info.offset.y + info.size.height
                        }
                        if (!onApp && start.y < size.height - dockHeight) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuSize = IntSize.Zero
                            menuItemBounds = Rect(start.x, start.y, start.x, start.y)
                            menuSlot = null
                            blankMenuPoint = start
                        }
                    } else if (menuSlot != null || blankMenuPoint != null) {
                        // The scrim is visual-only so apps stay draggable; close
                        // the menu when the same gesture turns into a tap/drag.
                        menuSlot = null
                        blankMenuPoint = null
                    }
                }
            }
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dateLabel,
                style = if (tabletLayout) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Medium,
                color = labelColor(wallpaper != null)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (tabletLayout) 24.dp else 16.dp,
                top = if (tabletLayout) 92.dp else 78.dp,
                end = if (tabletLayout) 24.dp else 16.dp,
                bottom = 128.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = slots,
                key = { _, slot -> slot.id }
            ) { index, slot ->
                val shift = if (previewSlots != null && index != dragIndex) {
                    val newIndex = previewSlots.indexOfFirst { it.id == slot.id }
                    val currentInfo = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == index }
                    val targetInfo = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == newIndex }
                    if (currentInfo != null && targetInfo != null) {
                        Offset(
                            (targetInfo.offset.x - currentInfo.offset.x).toFloat(),
                            (targetInfo.offset.y - currentInfo.offset.y).toFloat()
                        )
                    } else {
                        Offset.Zero
                    }
                } else {
                    Offset.Zero
                }
                DesktopSlotCell(
                    slot = slot,
                    index = index,
                    dragging = dragIndex == index,
                    dragOffset = dragOffset,
                    shift = shift,
                    dropTarget = dragIndex != index && dragTargetIndex == index,
                    folderTarget = folderTargetIndex == index,
                    onWallpaper = wallpaper != null,
                    tabletLayout = tabletLayout,
                    onLaunch = {
                        when (slot) {
                            is DesktopSlot.App -> launch(slot.entry)
                            is DesktopSlot.Folder -> openFolderId = slot.id
                        }
                    },
                    onDragStart = { startedIndex, bounds ->
                        dragIndex = startedIndex
                        dragTargetIndex = startedIndex
                        folderTargetIndex = -1
                        dragOffset = Offset.Zero
                        dragDistance = 0f
                        showMenuFor(startedIndex, bounds)
                    },
                    onDrag = { startedIndex, amount ->
                        if (dragIndex < 0) {
                            dragIndex = startedIndex
                            dragTargetIndex = startedIndex
                        }
                        dragOffset += amount
                        dragDistance += amount.getDistance()
                        if (dragDistance > dragThreshold && menuSlot != null) {
                            menuSlot = null
                        }
                        val info = gridState.layoutInfo.visibleItemsInfo
                        val startInfo = info.firstOrNull { it.index == dragIndex }
                        if (startInfo != null) {
                            val dragged = Rect(
                                startInfo.offset.x + dragOffset.x,
                                startInfo.offset.y + dragOffset.y,
                                startInfo.offset.x + dragOffset.x + startInfo.size.width,
                                startInfo.offset.y + dragOffset.y + startInfo.size.height
                            )
                            var bestFolder = -1
                            var bestRatio = 0f
                            info.forEach { item ->
                                if (item.index == dragIndex ||
                                    slots.getOrNull(item.index) !is DesktopSlot.App
                                ) {
                                    return@forEach
                                }
                                val target = Rect(
                                    item.offset.x.toFloat(),
                                    item.offset.y.toFloat(),
                                    (item.offset.x + item.size.width).toFloat(),
                                    (item.offset.y + item.size.height).toFloat()
                                )
                                val left = maxOf(dragged.left, target.left)
                                val top = maxOf(dragged.top, target.top)
                                val right = minOf(dragged.right, target.right)
                                val bottom = minOf(dragged.bottom, target.bottom)
                                if (right <= left || bottom <= top) return@forEach
                                val overlap = (right - left) * (bottom - top)
                                val smaller = minOf(
                                    dragged.width * dragged.height,
                                    target.width * target.height
                                )
                                if (smaller > 0f) {
                                    val ratio = overlap / smaller
                                    if (ratio > bestRatio) {
                                        bestRatio = ratio
                                        bestFolder = item.index
                                    }
                                }
                            }
                            folderTargetIndex = bestFolder.takeIf {
                                bestRatio >= folderMinOverlap && bestRatio < folderMaxOverlap
                            } ?: -1

                            if (folderTargetIndex >= 0) {
                                dragTargetIndex = folderTargetIndex
                            } else {
                                val center = Offset(
                                    startInfo.offset.x + startInfo.size.width / 2f,
                                    startInfo.offset.y + startInfo.size.height / 2f
                                ) + dragOffset
                                val target = info.minByOrNull { item ->
                                    val dx = item.offset.x + item.size.width / 2f - center.x
                                    val dy = item.offset.y + item.size.height / 2f - center.y
                                    dx * dx + dy * dy
                                }?.index ?: dragIndex
                                dragTargetIndex = target.coerceIn(
                                    0,
                                    (slots.lastIndex).coerceAtLeast(0)
                                )
                            }
                        }
                    },
                    onDragEnd = { handleDragEnd() },
                    onDragCancel = {
                        menuSlot = null
                        resetDrag()
                    }
                )
            }
        }

        LauncherDock(
            entries = dockEntries,
            onWallpaper = wallpaper != null,
            onLaunch = { launch(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    menuSlot?.let { slot ->
        // Visual only: a pointer-consuming scrim would block dragging an app
        // while its context menu is open. Taps outside are handled by the root
        // observer, so the grid stays draggable underneath.
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(8f)
                .background(Color.Black.copy(alpha = 0.08f))
        )
        Surface(
            modifier = Modifier
                .offset { menuPosition }
                .width(260.dp)
                .zIndex(9f)
                .onSizeChanged { menuSize = it },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                when (slot) {
                    is DesktopSlot.App -> {
                        val entry = slot.entry
                        val menuIndex = slots.indexOfFirst { it.id == slot.id }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Box(Modifier.size(40.dp)) {
                                    AppGlyph(entry, Modifier.matchParentSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        when (entry.kind) {
                                            LauncherKind.VIRTUAL ->
                                                AppLanguage.tr(
                                                    "虚拟应用 · 容器",
                                                    "Virtual app · container"
                                                )
                                            LauncherKind.SYSTEM ->
                                                AppLanguage.tr(
                                                    "系统应用 · 桌面",
                                                    "System app · desktop"
                                                )
                                            else -> AppLanguage.tr(
                                                "NestSpace 功能",
                                                "NestSpace feature"
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            menuShortcuts.take(4).forEach { shortcut ->
                                ShortcutAction(shortcut) {
                                    menuSlot = null
                                    onLaunchShortcut(entry.packageName, shortcut)
                                }
                            }
                            if (menuShortcuts.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            }
                            MenuAction(AppLanguage.tr("打开", "Open"), Icons.Default.OpenInNew) {
                                menuSlot = null
                                launch(entry)
                            }
                            if (folders.isNotEmpty()) {
                                MenuAction(
                                    AppLanguage.tr("添加到文件夹", "Add to folder"),
                                    Icons.Default.Folder
                                ) {
                                    menuSlot = null
                                    folderPickerApp = entry
                                }
                            }
                            if (menuIndex > 0) {
                                MenuAction(
                                    AppLanguage.tr("移到最前", "Move to front"),
                                    Icons.Default.VerticalAlignTop
                                ) {
                                    menuSlot = null
                                    addAppToTopLevelEnd(entry.id, front = true)
                                }
                            }
                            if (menuIndex >= 0 && menuIndex < slots.lastIndex) {
                                MenuAction(
                                    AppLanguage.tr("移到最后", "Move to back"),
                                    Icons.Default.VerticalAlignBottom
                                ) {
                                    menuSlot = null
                                    addAppToTopLevelEnd(entry.id, front = false)
                                }
                            }
                            MenuAction(
                                AppLanguage.tr("应用信息", "App info"),
                                Icons.Default.Info
                            ) {
                                menuSlot = null
                                if (entry.kind == LauncherKind.SYSTEM) {
                                    openSystemAppInfo(context, entry.packageName)
                                } else {
                                    appInfo = entry
                                }
                            }
                            when (entry.kind) {
                                LauncherKind.VIRTUAL -> MenuAction(
                                    AppLanguage.tr("卸载应用", "Uninstall app"),
                                    Icons.Default.DeleteOutline,
                                    danger = true
                                ) {
                                    menuSlot = null
                                    confirmUninstall = entry
                                }

                                LauncherKind.SYSTEM -> MenuAction(
                                    AppLanguage.tr("从桌面移除", "Remove from desktop"),
                                    Icons.Default.DeleteOutline,
                                    danger = true
                                ) {
                                    menuSlot = null
                                    confirmRemove = entry
                                }

                                else -> Unit
                            }
                        }
                    }

                    is DesktopSlot.Folder -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                FolderGlyph(
                                    folder = slot,
                                    onWallpaper = false,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        slot.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        AppLanguage.trCount(
                                            slot.apps.size,
                                            "${slot.apps.size} 个应用",
                                            "${slot.apps.size} app",
                                            "${slot.apps.size} apps"
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            MenuAction(
                                AppLanguage.tr("打开文件夹", "Open folder"),
                                Icons.Default.FolderOpen
                            ) {
                                menuSlot = null
                                openFolderId = slot.id
                            }
                            MenuAction(
                                AppLanguage.tr("重命名", "Rename"),
                                Icons.Default.CreateNewFolder
                            ) {
                                menuSlot = null
                                renamingFolder = slot
                                renameText = slot.name
                            }
                            MenuAction(
                                AppLanguage.tr("解散文件夹", "Dissolve folder"),
                                Icons.Default.DeleteOutline,
                                danger = true
                            ) {
                                menuSlot = null
                                dissolveFolder(slot.id)
                            }
                        }
                    }
                }
            }
        }
    }

    blankMenuPoint?.let { point ->
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(8f)
                .background(Color.Black.copy(alpha = 0.08f))
        )
        Surface(
            modifier = Modifier
                .offset { menuPosition }
                .width(260.dp)
                .zIndex(9f)
                .onSizeChanged { menuSize = it },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MenuAction(
                    AppLanguage.tr("新建文件夹", "New folder"),
                    Icons.Default.CreateNewFolder
                ) {
                    blankMenuPoint = null
                    createEmptyFolder()
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuAction(
                    AppLanguage.tr("模型设置", "Model settings"),
                    Icons.Default.SmartToy
                ) {
                    blankMenuPoint = null
                    onOpenModel()
                }
                MenuAction(
                    AppLanguage.tr("权限级别", "Permissions"),
                    Icons.Default.Security
                ) {
                    blankMenuPoint = null
                    onOpenPermissions()
                }
            }
        }
    }

    openFolder?.let { folder ->
        ModalBottomSheet(onDismissRequest = { openFolderId = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { addingToFolderId = folder.id }) {
                        Icon(Icons.Default.Add, contentDescription = AppLanguage.tr("添加应用到文件夹", "Add app to folder"))
                    }
                    IconButton(onClick = { openFolderId = null }) {
                        Icon(Icons.Default.Close, contentDescription = AppLanguage.tr("关闭", "Close"))
                    }
                }
                Text(
                    AppLanguage.trCount(
                        folder.apps.size,
                        "${folder.apps.size} 个应用 · 长按应用可移出",
                        "${folder.apps.size} app · long-press an app to remove it",
                        "${folder.apps.size} apps · long-press an app to remove it"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = folder.apps,
                        key = { it.id }
                    ) { app ->
                        FolderAppCell(
                            entry = app,
                            onClick = {
                                openFolderId = null
                                launch(app)
                            },
                            onLongPress = { folderAppMenu = app },
                            onDragOut = {
                                openFolderId = null
                                folderAppMenu = null
                                removeFromFolder(app.id)
                            }
                        )
                    }
                }
            }
        }
    }

    folderPickerApp?.let { app ->
        AlertDialog(
            onDismissRequest = { folderPickerApp = null },
            title = { Text(AppLanguage.tr("添加到文件夹", "Add to folder")) },
            text = {
                Column {
                    folders.forEach { folder ->
                        TextButton(
                            onClick = {
                                addToFolder(app.id, folder.id)
                                folderPickerApp = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(folder.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { folderPickerApp = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    addingFolder?.let { folder ->
        val candidates = appEntries.filter { entry ->
            folder.apps.none { it.id == entry.id }
        }
        AlertDialog(
            onDismissRequest = { addingToFolderId = null },
            title = { Text(AppLanguage.tr("添加到「${folder.name}」", "Add to \"${folder.name}\"")) },
            text = {
                if (candidates.isEmpty()) {
                    Text(AppLanguage.tr("桌面上没有其他应用", "No other apps on the desktop"))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        addToFolder(app.id, folder.id)
                                        addingToFolderId = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(36.dp)) {
                                    MiniAppGlyph(app)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(app.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addingToFolderId = null }) {
                    Text(AppLanguage.tr("完成", "Done"))
                }
            }
        )
    }

    folderAppMenu?.let { app ->
        AlertDialog(
            onDismissRequest = { folderAppMenu = null },
            title = { Text(app.label) },
            text = { Text(AppLanguage.tr("这个应用位于文件夹中。", "This app is inside a folder.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        folderAppMenu = null
                        openFolderId = null
                        launch(app)
                    }
                ) { Text(AppLanguage.tr("打开", "Open")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        removeFromFolder(app.id)
                        folderAppMenu = null
                    }
                ) { Text(AppLanguage.tr("移出文件夹", "Remove from folder")) }
            }
        )
    }

    renamingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { renamingFolder = null },
            title = { Text(AppLanguage.tr("重命名文件夹", "Rename folder")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        renameFolder(folder.id, renameText.trim())
                        renamingFolder = null
                    }
                ) { Text(AppLanguage.tr("保存", "Save")) }
            },
            dismissButton = {
                TextButton(onClick = { renamingFolder = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    appInfo?.let { entry ->
        val app = virtualApps.firstOrNull { it.packageName == entry.packageName }
        AlertDialog(
            onDismissRequest = { appInfo = null },
            icon = {
                Box(Modifier.size(52.dp)) {
                    AppGlyph(entry, Modifier.matchParentSize())
                }
            },
            title = { Text(entry.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                    if (app != null) {
                        Text(
                            AppLanguage.tr(
                                "版本 ${app.versionName ?: "-"}（${app.versionCode}）",
                                "Version ${app.versionName ?: "-"} (${app.versionCode})"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            AppLanguage.tr(
                                "${app.instances.size} 个实例 · 虚拟空间 1",
                                "${app.instances.size} instance(s) · Virtual space 1"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            AppLanguage.tr("系统应用", "System app"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        appInfo = null
                        onOpenAppManager()
                    }
                ) { Text(AppLanguage.tr("应用管理", "App manager")) }
            },
            dismissButton = {
                TextButton(onClick = { appInfo = null }) {
                    Text(AppLanguage.tr("关闭", "Close"))
                }
            }
        )
    }

    confirmUninstall?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(AppLanguage.tr("卸载应用", "Uninstall app")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将删除 ${entry.label} 及其全部实例数据，此操作无法撤销。",
                        "This deletes ${entry.label} and all instance data. " +
                            "This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Keep the icon until the engine reports success; the
                        // slot disappears from the resolved layout as soon as
                        // the installed-app list no longer contains it.
                        confirmUninstall = null
                        onUninstallVirtual(entry.packageName)
                    }
                ) { Text(AppLanguage.tr("卸载", "Uninstall")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    confirmRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(AppLanguage.tr("从桌面移除", "Remove from desktop")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "把 ${entry.label} 从桌面移除？应用仍保留在系统中。",
                        "Remove ${entry.label} from the desktop? The app stays " +
                            "installed on the system."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeApp(entry.id)
                        confirmRemove = null
                        onRemoveSystemApp(entry.packageName)
                    }
                ) { Text(AppLanguage.tr("移除", "Remove")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}
