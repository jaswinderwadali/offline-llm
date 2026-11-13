package ai.impact.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

sealed interface ModelDownload {
    val name: String

    data class Running(
        override val name: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val waiting: Boolean
    ) : ModelDownload {
        val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data class Failed(override val name: String, val message: String) : ModelDownload
}

data class DownloadFile(val url: String, val path: String, val size: Long)

/** Everything needed to download a model: its files, and a manifest describing it once installed. */
data class DownloadSpec(val id: String, val name: String, val files: List<DownloadFile>, val manifest: JSONObject? = null) {
    val sizeBytes: Long get() = files.sumOf { it.size }
}

/**
 * Downloads models with the system DownloadManager, so large files keep downloading (and
 * resume) when the app is in the background. Each model downloads into a hidden staging
 * folder and is moved into `root/<id>` once complete. Moving matters: clearing the system's
 * Downloads list deletes the files it knows about, which would otherwise delete installed models.
 */
class ModelDownloader(
    context: Context,
    private val scope: CoroutineScope,
    prefsName: String,
    /** Folder under the app's external Downloads dir; also referenced by the backup rules. */
    private val relativeRoot: String,
    /** Checks a finished download; returns an error message if it's unusable. */
    private val validate: (File) -> String?,
    /** Runs (on a background thread) once a model has been moved into place. */
    private val onInstalled: (id: String) -> Unit
) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val KEY_DOWNLOADS = "downloads"
        const val COMPLETE = ".complete"
        const val MANIFEST = "model.json"
        private const val SPACE_MARGIN = 200L * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val externalBase: File? = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    val root: File = File(externalBase ?: appContext.filesDir, relativeRoot)

    private val _downloads = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownload>> = _downloads.asStateFlow()

    private var pollJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) { if (activeDownloads().isNotEmpty()) startPolling() }
    }

    fun dirFor(id: String) = File(root, id)

    private fun stagingFor(id: String) = File(root, ".download-$id")

    fun freeSpaceBytes(): Long = try {
        root.mkdirs()
        StatFs(root.absolutePath).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    /** Returns an error message, or null once the download has been queued. */
    fun start(spec: DownloadSpec): String? {
        if (externalBase == null) return "Shared storage isn't available right now, so the model can't be downloaded."
        if (_downloads.value[spec.id] is ModelDownload.Running) return null
        if (spec.files.isEmpty()) return "There's nothing to download for ${spec.name}."
        val needed = spec.sizeBytes + SPACE_MARGIN
        if (freeSpaceBytes() < needed) {
            return "Not enough free storage. ${spec.name} needs about ${formatBytes(needed)}."
        }
        val dir = stagingFor(spec.id)
        dir.deleteRecursively()
        dir.mkdirs()
        // Written next to the downloads so it moves into place with them.
        spec.manifest?.let { File(dir, MANIFEST).writeText(it.toString()) }
        val ids = JSONArray()
        try {
            spec.files.forEachIndexed { index, file ->
                File(dir, file.path).parentFile?.mkdirs()
                val request = DownloadManager.Request(Uri.parse(file.url))
                    .setTitle(spec.name)
                    .setDescription(if (spec.files.size == 1) "Model download" else "Model · file ${index + 1} of ${spec.files.size}")
                    .setNotificationVisibility(
                        if (index == 0) DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        else DownloadManager.Request.VISIBILITY_VISIBLE
                    )
                    .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "$relativeRoot/${dir.name}/${file.path}")
                    .setAllowedOverRoaming(false)
                ids.put(downloadManager.enqueue(request))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't queue download", e)
            removeIds(LongArray(ids.length()) { ids.getLong(it) })
            dir.deleteRecursively()
            return "Couldn't start the download: ${e.message ?: "unknown error"}"
        }
        val record = JSONObject()
            .put("ids", ids)
            .put("sizes", JSONArray(spec.files.map { it.size }))
            .put("name", spec.name)
        writeDownloads(readDownloads().put(spec.id, record))
        _downloads.value = _downloads.value + (spec.id to ModelDownload.Running(spec.name, 0, spec.sizeBytes, waiting = true))
        startPolling()
        return null
    }

    fun cancel(id: String) {
        val all = readDownloads()
        activeDownloads()[id]?.let { removeIds(it.ids) }
        all.remove(id)
        writeDownloads(all)
        _downloads.value = _downloads.value - id
        stagingFor(id).deleteRecursively()
    }

    fun dismiss(id: String) {
        _downloads.value = _downloads.value - id
    }

    private fun removeIds(ids: LongArray) {
        if (ids.isNotEmpty()) runCatching { downloadManager.remove(*ids) }
    }

    private fun readDownloads(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_DOWNLOADS, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun writeDownloads(json: JSONObject) {
        prefs.edit().putString(KEY_DOWNLOADS, json.toString()).apply()
    }

    private class DownloadRecord(val ids: LongArray, val sizes: LongArray, val name: String)

    private fun activeDownloads(): Map<String, DownloadRecord> {
        val json = readDownloads()
        return json.keys().asSequence().mapNotNull { key ->
            val record = json.optJSONObject(key) ?: return@mapNotNull null
            val ids = record.optJSONArray("ids") ?: return@mapNotNull null
            val sizes = record.optJSONArray("sizes") ?: JSONArray()
            key to DownloadRecord(
                LongArray(ids.length()) { ids.getLong(it) },
                LongArray(ids.length()) { sizes.optLong(it) },
                record.optString("name", key)
            )
        }.toMap()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val active = activeDownloads()
                if (active.isEmpty()) break
                active.forEach { (id, record) -> poll(id, record) }
                delay(700)
            }
        }
    }

    private fun poll(id: String, record: DownloadRecord) {
        val ids = record.ids
        var downloaded = 0L
        var total = 0L
        var succeeded = 0
        var waiting = true
        var failure: String? = null
        val seen = HashSet<Long>()
        try {
            downloadManager.query(DownloadManager.Query().setFilterById(*ids))?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val soFarCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                while (c.moveToNext()) {
                    val downloadId = c.getLong(idCol)
                    seen += downloadId
                    val expected = record.sizes.getOrNull(ids.indexOf(downloadId)) ?: 0L
                    val soFar = c.getLong(soFarCol).coerceAtLeast(0)
                    downloaded += soFar
                    total += c.getLong(totalCol).takeIf { it > 0 } ?: expected
                    when (c.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> succeeded++
                        DownloadManager.STATUS_RUNNING -> waiting = false
                        DownloadManager.STATUS_FAILED -> failure = failureMessage(c.getInt(reasonCol))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download query failed", e)
            return
        }
        if (seen.size < ids.size && failure == null) {
            // The user cancelled it from the notification, or the system dropped it.
            failure = "The download was stopped."
        }
        val message = failure
        when {
            message != null -> {
                cancel(id)
                _downloads.value = _downloads.value + (id to ModelDownload.Failed(record.name, message))
            }
            succeeded == ids.size -> finish(id, record)
            else -> _downloads.value = _downloads.value +
                (id to ModelDownload.Running(record.name, downloaded, total, waiting && downloaded == 0L))
        }
    }

    private fun finish(id: String, record: DownloadRecord) {
        val staging = stagingFor(id)
        val target = dirFor(id)
        writeDownloads(readDownloads().apply { remove(id) })
        val problem = validate(staging)
        val moved = problem == null && run {
            target.deleteRecursively()
            staging.renameTo(target)
        }
        // The files have moved (or are useless), so drop the system's records of them.
        removeIds(record.ids)
        if (!moved) {
            staging.deleteRecursively()
            _downloads.value = _downloads.value + (id to ModelDownload.Failed(record.name, problem ?: "Couldn't save the model."))
            return
        }
        File(target, COMPLETE).writeText(System.currentTimeMillis().toString())
        _downloads.value = _downloads.value - id
        onInstalled(id)
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Ran out of storage space."
        DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR -> "The connection dropped. Try again."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage isn't available."
        401, 403 -> "This file needs a Hugging Face login, so it can't be downloaded."
        in 400..599 -> "The server returned an error ($reason)."
        else -> "The download failed. Check your connection and try again."
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024 * 1024)
    if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024)
    return String.format(Locale.US, "%.0f MB", mb.coerceAtLeast(1.0))
}
