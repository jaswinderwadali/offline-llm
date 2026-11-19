package ai.impact.ui.chat

import ai.impact.data.skills.ShareTarget
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

private const val TAG = "SkillShare"

/**
 * Opens [target]'s app with [text] ready to send. Tries each of the app's packages (WhatsApp
 * and WhatsApp Business, say), then the service's web link, then the system share sheet, so
 * the user always lands somewhere they can send it.
 *
 * With a [phone] number, WhatsApp opens that chat directly instead of asking for a contact.
 */
internal fun Context.sendToApp(target: ShareTarget, text: String, phone: String? = null) {
    if (text.isBlank()) return
    if (target == ShareTarget.Instagram) {
        // Instagram posts start from a photo, so the caption is copied, ready to paste.
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Caption", text))
        Toast.makeText(this, "Caption copied. Paste it into your post.", Toast.LENGTH_LONG).show()
    }

    for (pkg in target.packages) {
        val intent = if (target == ShareTarget.WhatsApp && phone != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(target.webUrl(text, phone)))
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        }
        if (tryStart(intent.setPackage(pkg))) return
    }

    // App not installed: its web link opens the app's site (or the app, if a browser hands it over).
    target.webUrl(text, phone)?.let { url ->
        if (tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return
    }

    Toast.makeText(this, "${target.label} isn't installed. Pick another app to send it.", Toast.LENGTH_SHORT).show()
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    tryStart(Intent.createChooser(send, "Send with"))
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    if (this !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    Log.w(TAG, "Not allowed to open ${intent.`package` ?: intent.data}", e)
    false
}
