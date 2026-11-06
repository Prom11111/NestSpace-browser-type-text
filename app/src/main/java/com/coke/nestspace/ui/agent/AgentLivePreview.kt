package com.coke.nestspace.ui.agent

import android.graphics.BitmapFactory
import android.util.Base64
import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.widget.FrameLayout
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.control.LivePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun LivePreviewCard(
    frame: LivePreview.Frame,
    modifier: Modifier,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val bitmap = rememberLiveBitmap(frame.base64, sampleSize = 2)
    Surface(
        modifier = modifier
            .width(112.dp)
            .height(196.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black,
        shadowElevation = 7.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = AppLanguage.tr("实时界面预览", "Live screen preview"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppLanguage.tr("实时", "LIVE"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6EE7B7),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    frame.label.ifBlank { AppLanguage.tr("容器", "Container") },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = AppLanguage.tr("关闭预览", "Close preview"),
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
internal fun LivePreviewBrowserCard(
    frame: LivePreview.Frame,
    modifier: Modifier,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    // Keep the browser card visually identical to the headless container
    // mirror window: same footprint, square corners, black title bar.
    Surface(
        modifier = modifier
            .width(115.dp)
            .height(255.dp),
        shape = RectangleShape,
        color = Color.Black,
        shadowElevation = 7.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppLanguage.tr("NestSpace · 浏览器", "NestSpace · Browser"),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = Color.White, fontSize = 22.sp)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                BrowserPreview(
                    url = frame.url,
                    modifier = Modifier.fillMaxSize()
                )
                // The WebView consumes touches; keep the body clickable and
                // leave the page itself non-interactive in the miniature.
                Box(Modifier.matchParentSize().clickable(onClick = onOpen))
            }
        }
    }
}

@Composable
private fun BrowserPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val hostWidth = metrics.widthPixels.coerceAtLeast(1)
    val hostHeight = metrics.heightPixels.coerceAtLeast(1)
    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true
                val web = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = BrowserSession.MOBILE_USER_AGENT
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isClickable = false
                    isFocusable = false
                    setBackgroundColor(android.graphics.Color.WHITE)
                    pivotX = 0f
                    pivotY = 0f
                    webView = this
                    loadUrl(url)
                }
                addView(web, FrameLayout.LayoutParams(hostWidth, hostHeight))
                // Render the page at the host display size, then scale the
                // whole WebView down into the preview slot. This keeps the
                // page layout identical to what the host browser sees.
                addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                    val width = (right - left).coerceAtLeast(1)
                    val height = (bottom - top).coerceAtLeast(1)
                    web.scaleX = width.toFloat() / hostWidth
                    web.scaleY = height.toFloat() / hostHeight
                }
            }
        },
        update = { frame ->
            val web = webView ?: (frame.getChildAt(0) as? WebView)
            if (web != null && url.isNotBlank() && web.url != url) web.loadUrl(url)
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

@Composable
internal fun LivePreviewDialog(frame: LivePreview.Frame, onDismiss: () -> Unit) {
    val isBrowser = frame.kind == LivePreview.KIND_BROWSER
    val bitmap = rememberLiveBitmap(frame.base64)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = if (isBrowser) RectangleShape else RoundedCornerShape(16.dp),
            color = Color.Black,
            shadowElevation = 10.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
            ) {
                if (isBrowser) {
                    BrowserPreview(
                        url = frame.url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(560.dp)
                    )
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = AppLanguage.tr("实时界面预览", "Live screen preview"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        AppLanguage.tr("暂时没有画面", "No frame yet"),
                        modifier = Modifier.padding(24.dp),
                        color = Color.White
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isBrowser) {
                            AppLanguage.tr("浏览器预览", "Browser preview")
                        } else {
                            AppLanguage.tr("实时界面预览", "Live screen preview")
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Text(
                        frame.label.ifBlank {
                            if (isBrowser) frame.url else AppLanguage.tr("容器", "Container")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = AppLanguage.tr("关闭预览", "Close preview"),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberLiveBitmap(
    base64: String,
    sampleSize: Int = 1
): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = base64
    ) {
        value = withContext(Dispatchers.Default) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }
                .getOrNull() ?: return@withContext null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    }
    return bitmap
}
