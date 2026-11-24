package ai.impact.ui.chat

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Languages a code block can be previewed in, as a rendered page. */
private val PREVIEWABLE = setOf("html", "htm", "xhtml", "svg")

/** Whether [language] / [code] is a page or drawing that can be shown rendered. */
fun isPreviewable(language: String, code: String): Boolean {
    val lang = language.lowercase()
    if (lang in PREVIEWABLE) return true
    val start = code.trimStart().take(200).lowercase()
    return lang in setOf("", "xml", "markup") && (start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<svg"))
}

/** An SVG on its own is wrapped in a page that centres it and fits it to the width. */
private fun pageFor(code: String): String {
    val trimmed = code.trim()
    return if (trimmed.startsWith("<svg", ignoreCase = true)) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
           <style>html,body{margin:0;height:100%;display:flex;align-items:center;justify-content:center;background:#fff}
           svg{max-width:100%;height:auto}</style></head><body>$trimmed</body></html>"""
    } else if ("<meta name=\"viewport\"" !in trimmed && "<meta name='viewport'" !in trimmed) {
        // Without a viewport, phones render pages zoomed out to desktop width.
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">$trimmed"
    } else trimmed
}

/**
 * Renders model-written HTML. Scripts run so interactive demos work, but the page has no access
 * to files or the app, and links open nowhere: it's a preview, not a browser.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun HtmlPreview(code: String, modifier: Modifier = Modifier, captureScroll: Boolean = true) {
    val page = pageFor(code)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    // Page links and form posts stay put; only the generated page is shown.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
                if (captureScroll) {
                    // Inside the chat list, a drag on the preview scrolls the page, not the chat.
                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                }
                tag = page
                loadDataWithBaseURL("https://preview.local/", page, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL("https://preview.local/", page, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() }
    )
}

/** The page full screen, to try it out properly. */
@Composable
fun HtmlPreviewDialog(code: String, title: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onDismiss)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close preview") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    HtmlPreview(code, Modifier.fillMaxSize(), captureScroll = false)
                }
            }
        }
    }
}
