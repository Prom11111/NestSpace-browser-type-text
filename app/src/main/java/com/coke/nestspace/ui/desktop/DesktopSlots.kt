package com.coke.nestspace.ui.desktop

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.coke.nestspace.R
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.AppShortcut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun DesktopSlotCell(
    slot: DesktopSlot,
    index: Int,
    dragging: Boolean,
    dragOffset: Offset,
    shift: Offset,
    dropTarget: Boolean,
    folderTarget: Boolean,
    onWallpaper: Boolean,
    tabletLayout: Boolean = false,
    onLaunch: () -> Unit,
    onDragStart: (Int, Rect) -> Unit,
    onDrag: (Int, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var suppressTapUntil by remember { mutableLongStateOf(0L) }
    var cellBounds by remember { mutableStateOf(Rect.Zero) }
    val animatedShift by animateOffsetAsState(
        targetValue = if (dragging) Offset.Zero else shift,
        animationSpec = tween(durationMillis = 150),
        label = "desktop-shift"
    )
    val haptic = LocalHapticFeedback.current
    val latestLaunch by rememberUpdatedState(onLaunch)
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestDragEnd by rememberUpdatedState(onDragEnd)
    val latestDragCancel by rememberUpdatedState(onDragCancel)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cellBounds = it.boundsInRoot() }
            .zIndex(if (dragging) 2f else 0f)
    ) {
        if (dropTarget) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = if (dragging) dragOffset.x else animatedShift.x
                    translationY = if (dragging) dragOffset.y else animatedShift.y
                    scaleX = if (dragging) 1.07f else 1f
                    scaleY = if (dragging) 1.07f else 1f
                    alpha = if (dragging) 0.92f else 1f
                }
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    if (SystemClock.uptimeMillis() >= suppressTapUntil) latestLaunch()
                }
                .pointerInput(slot.id, index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            suppressTapUntil = SystemClock.uptimeMillis() + 1_200
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestDragStart(index, cellBounds)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            latestDrag(index, amount)
                        },
                        onDragEnd = { latestDragEnd() },
                        onDragCancel = { latestDragCancel() }
                    )
                }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (slot) {
                is DesktopSlot.App -> {
                    Box(Modifier.size(if (tabletLayout) 64.dp else 56.dp)) {
                        AppGlyph(
                            entry = slot.entry,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        slot.entry.label,
                        style = if (tabletLayout) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = labelColor(onWallpaper),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                is DesktopSlot.Folder -> {
                    FolderGlyph(
                        folder = slot,
                        onWallpaper = onWallpaper,
                        modifier = Modifier.size(if (tabletLayout) 64.dp else 56.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        slot.name,
                        style = if (tabletLayout) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = labelColor(onWallpaper),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        if (folderTarget) {
            // Folder ghost: a translucent folder badge over the target icon
            // while a small overlap would create a new folder.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = AppLanguage.tr("新建文件夹", "New folder"),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.90f),
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

internal fun openSystemAppInfo(context: android.content.Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
internal fun labelColor(onWallpaper: Boolean): Color =
    if (onWallpaper) Color.White else MaterialTheme.colorScheme.onSurface

@Composable
internal fun LauncherDock(
    entries: List<LauncherEntry>,
    onWallpaper: Boolean,
    onLaunch: (LauncherEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val itemWidth = if (entries.size >= 5) 70.dp else 82.dp
    Row(
        modifier = modifier
            .widthIn(max = 390.dp)
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(
            space = 8.dp,
            alignment = Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.Top
    ) {
        entries.forEach { entry ->
            Column(
                modifier = Modifier
                    .width(itemWidth)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onLaunch(entry) }
                    .padding(vertical = 2.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppGlyph(entry, Modifier.size(50.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor(onWallpaper),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun AppGlyph(entry: LauncherEntry, modifier: Modifier = Modifier) {
    if (entry.kind == LauncherKind.AGENT) {
        // White rounded tile like the other launcher icons, with the assistant
        // portrait cut out on a transparent background.
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ai_assistant_cutout),
                contentDescription = entry.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        return
    }
    if (entry.icon != null) {
        Image(
            bitmap = entry.icon,
            contentDescription = entry.label,
            modifier = modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )
    } else if (entry.drawableRes != null) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Color(0xFFFDF6F0)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(entry.drawableRes),
                contentDescription = entry.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    } else if (entry.vectorIcon != null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                entry.vectorIcon,
                contentDescription = entry.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                entry.label.take(1),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun MiniAppGlyph(entry: LauncherEntry) {
    when {
        entry.icon != null -> Image(
            bitmap = entry.icon,
            contentDescription = entry.label,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(5.dp)),
            contentScale = ContentScale.Crop
        )

        entry.drawableRes != null -> Image(
            painter = painterResource(entry.drawableRes),
            contentDescription = entry.label,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(5.dp)),
            contentScale = ContentScale.Crop
        )

        entry.vectorIcon != null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                entry.vectorIcon,
                contentDescription = entry.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }

        else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                entry.label.take(1),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun FolderGlyph(
    folder: DesktopSlot.Folder,
    onWallpaper: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (onWallpaper) {
                    Color.White.copy(alpha = 0.88f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .padding(5.dp)
    ) {
        if (folder.apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(0.62f)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                repeat(2) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        repeat(2) { column ->
                            val app = folder.apps.getOrNull(row * 2 + column)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (app != null) MiniAppGlyph(app)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderAppCell(
    entry: LauncherEntry,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDragOut: () -> Unit
) {
    val latestClick by rememberUpdatedState(onClick)
    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestDragOut by rememberUpdatedState(onDragOut)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(entry.id) {
                val dragOutThreshold = 72.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (!change.pressed) break
                        if (change.position.y - down.position.y <= -dragOutThreshold) {
                            change.consume()
                            latestDragOut()
                            break
                        }
                    }
                }
            }
            .pointerInput(entry.id) {
                detectTapGestures(
                    onTap = { latestClick() },
                    onLongPress = { latestLongPress() }
                )
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp)) {
            AppGlyph(entry = entry, modifier = Modifier.matchParentSize())
        }
        Spacer(Modifier.height(5.dp))
        Text(
            entry.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun ShortcutAction(shortcut: AppShortcut, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = shortcut.icon
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            shortcut.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun MenuAction(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
