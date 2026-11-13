package ai.impact.image

import android.net.Uri
import ai.impact.models.DownloadFile
import ai.impact.models.DownloadSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A file in a Hugging Face repo. */
data class HubFile(val path: String, val size: Long)

/** A search result. [unsupportedReason] is a name-based hint; the real check happens on resolve. */
data class HubModel(val id: String, val downloads: Long, val likes: Long, val unsupportedReason: String?) {
    val owner: String get() = id.substringBefore('/')
    val name: String get() = id.substringAfter('/')
}

/** `owner/name`, optionally pinned to a revision and narrowed to a folder inside the repo. */
data class RepoRef(val repo: String, val revision: String = "main", val subPath: String = "")

/** One downloadable Stable Diffusion ONNX model inside a repo ([root] is "" or e.g. "sd-turbo/"). */
data class HubCandidate(val repo: String, val revision: String, val root: String, val files: List<HubFile>) {
    val sizeBytes: Long get() = files.sumOf { it.size }

    val name: String
        get() {
            val repoName = repo.substringAfter('/')
            val folder = root.trimEnd('/')
            return if (folder.isEmpty()) repoName else "$repoName · $folder"
        }

    /** Folder-safe, stable id so downloading the same model twice replaces it rather than duplicating. */
    val id: String
        get() {
            val raw = "$repo/$root".lowercase().trimEnd('/')
            val slug = raw.replace(Regex("[^a-z0-9.-]+"), "_").take(60)
            return "hf-$slug-${Integer.toHexString(raw.hashCode())}"
        }

    fun fileUrl(path: String): String {
        val builder = Uri.Builder().scheme("https").authority("huggingface.co")
        repo.split('/').forEach { builder.appendPath(it) }
        builder.appendPath("resolve").appendPath(revision)
        (root + path).split('/').filter { it.isNotEmpty() }.forEach { builder.appendPath(it) }
        return builder.build().toString()
    }
}

/** Which kind of model a Hugging Face search or download is for. */
enum class ModelKind { Chat, Image }

/** A single-file GGUF chat model in a repo (one quantisation). */
data class GgufFile(val repo: String, val revision: String, val path: String, val size: Long) {
    val fileName: String get() = path.substringAfterLast('/')

    /** The quantisation, e.g. "Q4_K_M", read from the file name. */
    val quant: String?
        get() = Regex("""(?i)(?:^|[-_.])((?:UD-)?(?:IQ\d\w*|Q\d\w*|F16|BF16|F32|FP16))(?=\.gguf$)""")
            .find(fileName)?.groupValues?.get(1)?.uppercase()

    val name: String get() = fileName.removeSuffix(".gguf").removeSuffix(".GGUF")

    val id: String
        get() {
            val raw = "$repo/$path".lowercase()
            val slug = name.lowercase().replace(Regex("[^a-z0-9.-]+"), "_").take(60)
            return "hf-$slug-${Integer.toHexString(raw.hashCode())}"
        }

    fun url(): String {
        val builder = Uri.Builder().scheme("https").authority("huggingface.co")
        repo.split('/').forEach { builder.appendPath(it) }
        builder.appendPath("resolve").appendPath(revision)
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.appendPath(it) }
        return builder.build().toString()
    }

    fun toDownloadSpec(): DownloadSpec {
        val manifest = org.json.JSONObject()
            .put("name", name)
            .put("file", fileName)
            .put("source", "huggingface")
            .put("repo", repo)
        return DownloadSpec(id, name, listOf(DownloadFile(url(), fileName, size)), manifest)
    }
}

sealed interface GgufResolution {
    data class Found(val repo: String, val files: List<GgufFile>) : GgufResolution
    data class Unsupported(val repo: String, val reason: String) : GgufResolution
}

sealed interface HubResolution {
    data class Found(val repo: String, val candidates: List<HubCandidate>) : HubResolution
    data class Unsupported(val repo: String, val reason: String) : HubResolution
}

/** Pure logic for reading repo references and picking out usable models; no I/O, so it's unit-tested. */
object HubParsing {

    private val COMPONENTS = listOf("text_encoder", "unet", "vae_decoder", "tokenizer")
    private val REPO_PART = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
    private val WEIGHT_DATA = Regex(""".*(\.onnx_data|\.onnx\.data|\.data|weights\.pb)$""")
    private val KEEP_EXACT = setOf(
        "model.onnx", "config.json", "vocab.json", "merges.txt", "special_tokens_map.json", "tokenizer_config.json"
    )

    /**
     * Accepts `owner/name`, `huggingface.co/owner/name`, or a full link to a repo, a folder
     * (`/tree/<rev>/<folder>`) or a file (`/blob/<rev>/...`). Returns null for anything else.
     */
    fun parseRepoReference(input: String): RepoRef? {
        var text = input.trim()
        if (text.isEmpty() || text.contains(' ')) return null
        text = text.substringBefore('?').substringBefore('#')
        val hasHost = Regex("""^(https?://)?(www\.)?(huggingface\.co|hf\.co)/""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        text = text.replace(Regex("""^(https?://)?(www\.)?(huggingface\.co|hf\.co)/""", RegexOption.IGNORE_CASE), "")
        val parts = text.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        if (parts[0] in setOf("datasets", "spaces", "docs", "models", "api")) return null
        // A bare "a/b/c" without the host is ambiguous, so only links may carry extra path parts.
        if (!hasHost && parts.size != 2) return null
        if (!REPO_PART.matches(parts[0]) || !REPO_PART.matches(parts[1])) return null
        val repo = "${parts[0]}/${parts[1]}"
        if (parts.size >= 4 && parts[2] in setOf("tree", "blob", "resolve")) {
            val sub = parts.drop(4).joinToString("/")
            return RepoRef(repo, parts[3], normaliseSubPath(sub, isFile = parts[2] != "tree"))
        }
        return RepoRef(repo)
    }

    /** A link to a file or component folder means "the model this belongs to", so walk up to its root. */
    private fun normaliseSubPath(path: String, isFile: Boolean): String {
        var segments = path.split('/').filter { it.isNotEmpty() }
        if (isFile && segments.isNotEmpty()) segments = segments.dropLast(1)
        while (segments.isNotEmpty() && segments.last() in COMPONENTS + listOf("vae_encoder", "scheduler", "text_encoder_2", "tokenizer_2")) {
            segments = segments.dropLast(1)
        }
        return if (segments.isEmpty()) "" else segments.joinToString("/") + "/"
    }

    /** A quick warning from the repo name, for search results, before any files are checked. */
    fun unsupportedReason(repoId: String): String? {
        val n = repoId.lowercase()
        return when {
            "sdxl" in n || "-xl" in n || "_xl" in n || n.endsWith("xl") || "xl-" in n -> "SDXL"
            "stable-diffusion-3" in n || "sd3" in n -> "SD 3"
            "flux" in n -> "FLUX"
            "inpaint" in n -> "Inpainting"
            "controlnet" in n -> "ControlNet"
            "lcm" in n -> "LCM"
            "video" in n || "wan2" in n -> "Video"
            else -> null
        }
    }

    /** Parameter count from a name like "Qwen2.5-7B-Instruct" → 7.0, or null if none is given. */
    fun parameterBillions(repoId: String): Double? =
        Regex("""(?i)(?:^|[^a-z0-9.])(\d+(?:\.\d+)?)b(?:[^a-z]|$)""").findAll(repoId)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .lastOrNull()

    /** A quick warning for chat search results whose names say they're too big for phones. */
    fun chatUnsupportedReason(repoId: String): String? {
        val billions = parameterBillions(repoId) ?: return null
        return if (billions > 5) "${billions.toString().removeSuffix(".0")}B · too big for phones" else null
    }

    /**
     * Single-file GGUF chat models in the repo, smallest first. Split files ("-00001-of-00003")
     * and vision projectors ("mmproj") can't be loaded on their own, so they're left out.
     */
    fun ggufFiles(ref: RepoRef, files: List<HubFile>): GgufResolution {
        val gguf = files.filter { it.path.endsWith(".gguf", ignoreCase = true) }
            .filter { ref.subPath.isEmpty() || it.path.startsWith(ref.subPath) }
        val usable = gguf.filterNot { f ->
            val name = f.path.substringAfterLast('/').lowercase()
            "mmproj" in name || Regex("""-\d{5}-of-\d{5}""").containsMatchIn(name)
        }
        if (usable.isNotEmpty()) {
            return GgufResolution.Found(ref.repo, usable.map { GgufFile(ref.repo, ref.revision, it.path, it.size) }.sortedBy { it.size })
        }
        val names = files.map { it.path.lowercase() }
        val reason = when {
            gguf.isNotEmpty() -> "This model is split into several files, which TinyAI can't load. Look for a single-file version."
            names.any { it.endsWith("unet/model.onnx") } -> "This is an image model. Download it from the Image tab instead."
            names.any { it.endsWith(".safetensors") || it.endsWith(".bin") } ->
                "This model isn't in GGUF format. Search for a GGUF version of it (its name plus \"gguf\")."
            else -> "No GGUF model files found in this repo."
        }
        return GgufResolution.Unsupported(ref.repo, reason)
    }

    fun findCandidates(ref: RepoRef, files: List<HubFile>): HubResolution {
        val paths = files.associateBy { it.path }
        var roots = files.map { it.path }
            .filter { it == "unet/model.onnx" || it.endsWith("/unet/model.onnx") }
            .map { it.removeSuffix("unet/model.onnx") }
        if (ref.subPath.isNotEmpty()) {
            roots = roots.filter { it.startsWith(ref.subPath) || ref.subPath.startsWith(it) }
        }
        val sdxlRoots = roots.filter { root -> files.any { it.path.startsWith(root + "text_encoder_2/") } }
        val complete = (roots - sdxlRoots.toSet()).filter { root ->
            StableDiffusionPipeline.REQUIRED_FILES.all { (root + it) in paths }
        }

        if (complete.isEmpty()) {
            return HubResolution.Unsupported(ref.repo, explainMissing(ref, files, roots, sdxlRoots))
        }
        val candidates = complete.map { root ->
            val kept = files.filter { f ->
                val rel = f.path.removePrefix(root)
                f.path.startsWith(root) && rel.substringBefore('/') in COMPONENTS + listOf("scheduler") &&
                    rel.count { it == '/' } == 1 && keep(rel.substringAfter('/'))
            }.map { HubFile(it.path.removePrefix(root), it.size) }
            HubCandidate(ref.repo, ref.revision, root, kept)
        }.sortedBy { it.sizeBytes }
        return HubResolution.Found(ref.repo, candidates)
    }

    private fun keep(fileName: String): Boolean =
        fileName in KEEP_EXACT || fileName == "scheduler_config.json" || WEIGHT_DATA.matches(fileName)

    private fun explainMissing(ref: RepoRef, files: List<HubFile>, roots: List<String>, sdxlRoots: List<String>): String {
        val names = files.map { it.path.lowercase() }
        return when {
            sdxlRoots.isNotEmpty() || names.any { it.startsWith("text_encoder_2/") || "/text_encoder_2/" in it } ->
                "This is an SDXL model. TinyAI runs Stable Diffusion 1.x and 2.x models, which are small enough for phones."
            names.any { it.endsWith(".gguf") } ->
                "This repo has GGUF files, which are chat models. Load those from the chat Model center instead."
            roots.isNotEmpty() -> {
                val root = roots.first()
                val missing = StableDiffusionPipeline.REQUIRED_FILES.filterNot { (root + it) in files.map { f -> f.path } }
                "This ONNX export is incomplete: it's missing ${missing.joinToString()}."
            }
            names.any { it.endsWith(".onnx") } ->
                "This repo has ONNX files, but not a complete Stable Diffusion pipeline (text_encoder, unet, vae_decoder and tokenizer)."
            names.any { it.endsWith(".safetensors") || it.endsWith(".ckpt") || it.endsWith(".bin") } ->
                "This model isn't converted to ONNX. Search for an ONNX version of it (its name plus \"onnx\")."
            ref.subPath.isNotEmpty() -> "No Stable Diffusion ONNX model in ${ref.subPath.trimEnd('/')}."
            else -> "No Stable Diffusion ONNX model found in this repo."
        }
    }
}

class HubException(message: String) : IOException(message)

/** Minimal Hugging Face Hub client: public model search and file listings, no account needed. */
class HuggingFaceClient {

    companion object {
        private const val API = "https://huggingface.co/api/models"
        private const val MAX_TREE_PAGES = 10
    }

    suspend fun search(query: String, kind: ModelKind = ModelKind.Image, limit: Int = 30): List<HubModel> = withContext(Dispatchers.IO) {
        val params = buildList {
            when (kind) {
                ModelKind.Image -> {
                    add("filter" to "onnx")
                    add("pipeline_tag" to "text-to-image")
                }
                ModelKind.Chat -> {
                    add("filter" to "gguf")
                    add("pipeline_tag" to "text-generation")
                    // Phones can't run much past 4B parameters, so don't show the 27B giants.
                    add("num_parameters" to "min:0,max:4B")
                }
            }
            if (query.isNotBlank()) add("search" to query.trim())
            add("sort" to "downloads")
            add("direction" to "-1")
            add("limit" to limit.toString())
        }
        val url = API + "?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val array = JSONArray(get(url).body)
        List(array.length()) { i ->
            val m = array.getJSONObject(i)
            val id = m.getString("id")
            val reason = if (kind == ModelKind.Image) HubParsing.unsupportedReason(id) else HubParsing.chatUnsupportedReason(id)
            HubModel(id, m.optLong("downloads"), m.optLong("likes"), reason)
        }
    }

    suspend fun resolve(ref: RepoRef): HubResolution = withContext(Dispatchers.IO) {
        accessProblem(ref)?.let { return@withContext HubResolution.Unsupported(ref.repo, it) }
        HubParsing.findCandidates(ref, listFiles(ref))
    }

    suspend fun resolveGguf(ref: RepoRef): GgufResolution = withContext(Dispatchers.IO) {
        accessProblem(ref)?.let { return@withContext GgufResolution.Unsupported(ref.repo, it) }
        HubParsing.ggufFiles(ref, listFiles(ref))
    }

    /** Why the repo's files can't be downloaded without an account, or null if they can. */
    private fun accessProblem(ref: RepoRef): String? {
        val info = JSONObject(get("$API/${ref.repo}").body)
        if (info.optBoolean("private", false)) return "This repo is private."
        // "gated" is false for open repos, or "auto"/"manual" when a license must be accepted first.
        val gated = info.opt("gated")
        if (gated is String && gated != "false") {
            return "This model needs you to sign in to Hugging Face and accept its license, which TinyAI can't do yet."
        }
        return null
    }

    private fun listFiles(ref: RepoRef): List<HubFile> {
        val files = mutableListOf<HubFile>()
        var next: String? = "$API/${ref.repo}/tree/${URLEncoder.encode(ref.revision, "UTF-8")}?recursive=true"
        var pages = 0
        while (next != null && pages < MAX_TREE_PAGES) {
            val response = get(next)
            val array = JSONArray(response.body)
            for (i in 0 until array.length()) {
                val e = array.getJSONObject(i)
                if (e.optString("type") == "file") files += HubFile(e.getString("path"), e.optLong("size"))
            }
            next = response.link?.let { Regex("""<([^>]+)>;\s*rel="next"""").find(it)?.groupValues?.get(1) }
            pages++
        }
        return files
    }

    private class Response(val body: String, val link: String?)

    private fun get(url: String): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "TinyAI-Android")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw HubException("Couldn't reach Hugging Face. Check your connection.")
            }
            when (code) {
                in 200..299 -> return Response(connection.inputStream.bufferedReader().use { it.readText() }, connection.getHeaderField("Link"))
                401, 403 -> throw HubException("This repo is private or needs a Hugging Face login.")
                404 -> throw HubException("Nothing found on Hugging Face at that name.")
                429 -> throw HubException("Hugging Face is rate limiting requests. Try again in a minute.")
                else -> throw HubException("Hugging Face returned an error ($code).")
            }
        } finally {
            connection.disconnect()
        }
    }
}
