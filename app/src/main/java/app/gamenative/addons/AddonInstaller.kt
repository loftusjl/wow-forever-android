package app.gamenative.addons

import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Where an installed addon came from, kept beside the game so it survives reinstalling the app. */
data class SourceRecord(
    val provider: Provider,
    val id: String,
    val name: String,
    val version: String,
    val folders: List<String>,
    /** For GitHub: the release file installed last time, so updates pick the same kind of file. */
    val assetName: String? = null,
)

data class UpdateInfo(val record: SourceRecord, val newVersion: String)

object AddonSources {
    private const val FILE = ".addon-sources.json"

    private fun file(gameRoot: File) = File(AddonManager.addonsDir(gameRoot).parentFile, FILE)

    fun load(gameRoot: File): List<SourceRecord> = runCatching {
        val array = JSONArray(file(gameRoot).takeIf { it.isFile }?.readText() ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            SourceRecord(
                Provider.of(o.optString("provider")) ?: return@mapNotNull null,
                o.optString("id"), o.optString("name"), o.optString("version"), o.optJSONArray("folders").strings(),
                o.optString("asset").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    fun record(gameRoot: File, record: SourceRecord) {
        val all = load(gameRoot).filterNot { it.provider == record.provider && it.id == record.id }.toMutableList()
        // An addon folder has one source: a fresh install replaces whatever tracked those folders before.
        val kept = all.mapNotNull { r -> r.copy(folders = r.folders - record.folders.toSet()).takeIf { it.folders.isNotEmpty() } }
        write(gameRoot, kept + record)
    }

    /** Forget records whose folders are all gone, e.g. after a removal. */
    fun prune(gameRoot: File, installedFolders: Set<String>) {
        val all = load(gameRoot)
        val kept = all.mapNotNull { r -> r.copy(folders = r.folders.filter { it in installedFolders }).takeIf { it.folders.isNotEmpty() } }
        if (kept != all) write(gameRoot, kept)
    }

    private fun write(gameRoot: File, records: List<SourceRecord>) {
        val array = JSONArray()
        for (r in records) {
            array.put(
                JSONObject().put("provider", r.provider.key).put("id", r.id).put("name", r.name)
                    .put("version", r.version).put("folders", JSONArray(r.folders)).put("asset", r.assetName ?: ""),
            )
        }
        val target = file(gameRoot)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(array.toString())
            tmp.delete()
        }
    }
}

object AddonInstaller {
    fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads [url] to [dest], checking the MD5 when one is given. */
    fun download(url: String, dest: File, expectedMd5: String?) {
        require(url.startsWith("https://")) { "Only https downloads are allowed" }
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).header("User-Agent", "WoW-Forever-Android").build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
            val body = response.body ?: throw IOException("Download was empty")
            dest.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        if (expectedMd5 != null && !md5(dest).equals(expectedMd5, ignoreCase = true)) {
            dest.delete()
            throw IOException("The download didn't match its checksum, so it was discarded")
        }
    }

    /** Downloads and installs [file], then remembers where it came from. Returns the installed folders. */
    fun install(
        gameRoot: File,
        cacheDir: File,
        provider: Provider,
        id: String,
        name: String,
        file: RemoteFile,
    ): List<String> {
        val zip = File(cacheDir, "addon-download-${System.nanoTime()}.zip")
        try {
            download(file.url, zip, file.md5)
            val folders = AddonManager.installZip(zip, AddonManager.addonsDir(gameRoot))
            AddonSources.record(gameRoot, SourceRecord(provider, id, name, file.version, folders, file.assetName))
            return folders
        } finally {
            zip.delete()
        }
    }

    fun installCatalog(gameRoot: File, cacheDir: File, addon: CatalogAddon): List<String> {
        val file = when (addon.provider) {
            Provider.WOWINTERFACE -> WowInterfaceProvider.resolve(addon.id)
            Provider.TUKUI -> RemoteFile(addon.downloadUrl ?: throw IOException("No download for ${addon.name}"), null, addon.version)
            Provider.GITHUB, Provider.URL -> throw IllegalArgumentException("${addon.provider.label} addons install from a URL")
        }
        return install(gameRoot, cacheDir, addon.provider, addon.id, addon.name, file)
    }

    /** Installs a GitHub repo's latest release, or a direct https link to a zip. */
    fun installFromUrl(gameRoot: File, cacheDir: File, input: String): List<String> {
        val text = input.trim()
        val repo = GitHubProvider.parseRepo(text)
        if (repo != null) {
            val file = GitHubProvider.latest(repo)
            return install(gameRoot, cacheDir, Provider.GITHUB, "${repo.owner}/${repo.repo}", repo.repo, file)
        }
        if (!text.startsWith("https://")) throw IOException("Enter a GitHub repository or an https link to a zip")
        val zip = File(cacheDir, "addon-download-${System.nanoTime()}.zip")
        try {
            download(text, zip, null)
            val folders = AddonManager.installZip(zip, AddonManager.addonsDir(gameRoot))
            val fingerprint = headerFingerprint(text) ?: "sha256:${sha256(zip)}"
            AddonSources.record(gameRoot, SourceRecord(Provider.URL, text, folders.first(), fingerprint, folders))
            return folders
        } finally {
            zip.delete()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** What a server says identifies this file's current content, or null when it says nothing usable. */
    fun fingerprintOf(etag: String?, lastModified: String?, length: Long?): String? = when {
        !etag.isNullOrBlank() -> "etag:$etag"
        !lastModified.isNullOrBlank() -> "modified:$lastModified"
        length != null && length > 0 -> "size:$length"
        else -> null
    }

    private fun headerFingerprint(url: String): String? {
        val request = Request.Builder().url(url).head().header("User-Agent", "WoW-Forever-Android").build()
        return runCatching {
            Net.http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null else fingerprintOf(r.header("ETag"), r.header("Last-Modified"), r.header("Content-Length")?.toLongOrNull())
            }
        }.getOrNull()
    }

    /** The link's current fingerprint, in the same form that was recorded (headers, or a content hash). */
    private fun currentFingerprint(url: String, recorded: String, cacheDir: File): String? {
        if (!recorded.startsWith("sha256:")) return headerFingerprint(url)
        val zip = File(cacheDir, "addon-check-${System.nanoTime()}.zip")
        return try {
            download(url, zip, null)
            "sha256:${sha256(zip)}"
        } catch (_: Exception) {
            null
        } finally {
            zip.delete()
        }
    }

    /** Tracked addons whose source has a different version than the one installed. GitHub is checked per addon. */
    fun findUpdates(gameRoot: File, cacheDir: File, catalog: List<CatalogAddon>, checkGitHub: Boolean): List<UpdateInfo> {
        val byKey = catalog.associateBy { it.key }
        val installed = AddonManager.listInstalled(gameRoot).map { it.folder }.toSet()
        return AddonSources.load(gameRoot).filter { r -> r.folders.any { it in installed } }.mapNotNull { r ->
            val latest = when (r.provider) {
                Provider.GITHUB -> if (checkGitHub) {
                    runCatching { GitHubProvider.latest(GitHubProvider.parseRepo("github.com/${r.id}") ?: return@mapNotNull null, r.assetName).version }.getOrNull()
                } else null
                Provider.URL -> if (checkGitHub) currentFingerprint(r.id, r.version, cacheDir) else null
                else -> byKey["${r.provider.key}:${r.id}"]?.version
            }
            if (latest != null && latest.isNotEmpty() && latest != r.version) UpdateInfo(r, latest) else null
        }
    }

    fun update(gameRoot: File, cacheDir: File, info: UpdateInfo, catalog: List<CatalogAddon>): List<String> {
        val r = info.record
        return when (r.provider) {
            Provider.GITHUB -> install(gameRoot, cacheDir, r.provider, r.id, r.name, GitHubProvider.latest(GitHubProvider.parseRepo("github.com/${r.id}")!!, r.assetName))
            Provider.URL -> install(gameRoot, cacheDir, r.provider, r.id, r.name, RemoteFile(r.id, null, info.newVersion))
            else -> installCatalog(gameRoot, cacheDir, catalog.first { it.key == "${r.provider.key}:${r.id}" })
        }
    }
}
