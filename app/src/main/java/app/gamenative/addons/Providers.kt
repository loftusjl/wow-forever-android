package app.gamenative.addons

import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class Provider(val key: String, val label: String) {
    WOWINTERFACE("wowinterface", "WoWInterface"),
    TUKUI("tukui", "Tukui"),
    GITHUB("github", "GitHub"),
    URL("url", "Web link"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class CatalogAddon(
    val provider: Provider,
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val folders: List<String>,
    val downloadsMonthly: Long,
    val downloadsTotal: Long,
    val gameVersions: List<String>,
    val infoUrl: String?,
    val summary: String?,
    val images: List<String> = emptyList(),
    /** Set only for catalogs that list the file in the index (Tukui); WoWInterface resolves it on demand. */
    val downloadUrl: String? = null,
    /** Last update, epoch millis; 0 when the source doesn't say. */
    val updated: Long = 0,
) {
    val key get() = "${provider.key}:$id"

    /** Retail 12.x is Midnight and later, which the community reports WoW Forever shares its addon API with. */
    val isMidnight get() = gameVersions.any { it.startsWith("12.") }
}

/** A remote file ready to download. */
data class RemoteFile(val url: String, val md5: String?, val version: String, val assetName: String? = null)

/** What the detail view shows. [markdown] is false for WoWInterface descriptions, which are BBCode. */
data class AddonDetails(
    val description: String?,
    val changelog: String?,
    val images: List<String>,
    val homepage: String?,
    val markdown: Boolean,
)

data class GitHubRepo(val owner: String, val repo: String)

object WowInterfaceProvider {
    private const val BASE = "https://api.mmoui.com/v3/game/WOW"
    const val LIST_URL = "$BASE/filelist.json"
    private const val DETAILS_URL = "$BASE/filedetails/"
    private const val MAX_CACHE_AGE_MS = 12L * 60 * 60 * 1000

    fun parseFileList(json: String): List<CatalogAddon> {
        val array = JSONArray(json)
        val out = ArrayList<CatalogAddon>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("UID").takeIf { it.isNotEmpty() } ?: continue
            val compat = o.optJSONArray("UICompatibility")
            out.add(
                CatalogAddon(
                    provider = Provider.WOWINTERFACE,
                    id = id,
                    name = o.optString("UIName"),
                    author = o.optString("UIAuthorName"),
                    version = o.optString("UIVersion"),
                    folders = o.optJSONArray("UIDir").strings(),
                    downloadsMonthly = o.optString("UIDownloadMonthly").toLongOrNull() ?: 0,
                    downloadsTotal = o.optString("UIDownloadTotal").toLongOrNull() ?: 0,
                    gameVersions = (0 until (compat?.length() ?: 0)).mapNotNull { compat?.optJSONObject(it)?.optString("version")?.takeIf { v -> v.isNotEmpty() } },
                    infoUrl = o.optString("UIFileInfoURL").takeIf { it.isNotEmpty() },
                    summary = null,
                    images = o.optJSONArray("UIIMGs").strings().filter { it.startsWith("https://") },
                    updated = o.optLong("UIDate", 0),
                ),
            )
        }
        return out
    }

    fun parseFileDetails(json: String): RemoteFile {
        val o = JSONArray(json).optJSONObject(0) ?: throw IOException("WoWInterface returned no file details")
        val url = o.optString("UIDownload").takeIf { it.isNotEmpty() } ?: throw IOException("WoWInterface has no download for this addon")
        return RemoteFile(url, o.optString("UIMD5").takeIf { it.isNotEmpty() }, o.optString("UIVersion"))
    }

    /** The catalog, from a cache up to 12 hours old, else from the network (falling back to a stale cache if offline). */
    fun catalog(cacheDir: File): List<CatalogAddon> {
        val cache = File(cacheDir, "wowinterface-filelist.json")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_CACHE_AGE_MS
        if (!fresh) {
            try {
                val text = httpText(LIST_URL)
                cache.writeText(text)
                return parseFileList(text)
            } catch (e: Exception) {
                if (!cache.isFile) throw e
            }
        }
        return parseFileList(cache.readText())
    }

    fun resolve(id: String): RemoteFile = parseFileDetails(httpText("$DETAILS_URL$id.json"))

    fun parseDetails(json: String, fallbackImages: List<String>, infoUrl: String?): AddonDetails {
        val o = JSONArray(json).optJSONObject(0) ?: throw IOException("WoWInterface returned no details")
        val description = o.optString("UIDescription").takeIf { it.isNotBlank() }
        val images = (o.optJSONArray("UIIMGs").strings() + fallbackImages + Markup.extractImageUrls(description.orEmpty()))
            .filter { it.startsWith("https://") }.distinct()
        return AddonDetails(
            description = description,
            changelog = o.optString("UIChangeLog").takeIf { it.isNotBlank() },
            images = images,
            homepage = infoUrl,
            markdown = false,
        )
    }

    fun details(id: String, fallbackImages: List<String> = emptyList(), infoUrl: String? = null): AddonDetails =
        parseDetails(httpText("$DETAILS_URL$id.json"), fallbackImages, infoUrl)

    fun details(addon: CatalogAddon): AddonDetails = details(addon.id, addon.images, addon.infoUrl)
}

object TukuiProvider {
    private const val URL = "https://api.tukui.org/v1/addons"

    fun parse(json: String): List<CatalogAddon> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.optString("slug").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            CatalogAddon(
                provider = Provider.TUKUI,
                id = slug,
                name = o.optString("name", slug),
                author = o.optString("author"),
                version = o.optString("version"),
                folders = emptyList(),
                downloadsMonthly = 0,
                downloadsTotal = 0,
                gameVersions = o.optJSONArray("patch").strings(),
                infoUrl = o.optString("web_url").takeIf { it.isNotEmpty() },
                summary = o.optString("small_desc").takeIf { it.isNotEmpty() },
                downloadUrl = url,
                updated = runCatching { java.time.LocalDate.parse(o.optString("last_update")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0),
            )
        }
    }

    fun catalog(): List<CatalogAddon> = parse(httpText(URL))

    private const val MAX_CHANGELOG_CHARS = 8000

    /** Tukui's changelog files can run to hundreds of KB; the recent releases are all anyone reads. */
    fun parseDetails(json: String, changelog: String?): AddonDetails {
        val o = JSONObject(json)
        val description = o.optString("desc").takeIf { it.isNotBlank() } ?: o.optString("small_desc").takeIf { it.isNotBlank() }
        val images = (listOf(o.optString("screenshot_url")) + o.optJSONArray("gallery_url").strings() + Markup.extractImageUrls(description.orEmpty()))
            .filter { it.startsWith("https://") }.distinct()
        return AddonDetails(
            description = description,
            changelog = changelog?.takeIf { it.isNotBlank() }?.let { if (it.length > MAX_CHANGELOG_CHARS) it.take(MAX_CHANGELOG_CHARS).substringBeforeLast('\n') + "\n\n…" else it },
            images = images,
            homepage = o.optString("web_url").takeIf { it.isNotEmpty() },
            markdown = true,
        )
    }

    fun details(slug: String): AddonDetails {
        val json = httpText("https://api.tukui.org/v1/addon/$slug")
        val changelogUrl = JSONObject(json).optString("changelog_url").takeIf { it.startsWith("https://") }
        return parseDetails(json, changelogUrl?.let { runCatching { httpText(it) }.getOrNull() })
    }
}

object GitHubProvider {
    private val EXCLUDED_FLAVORS = Regex("""classic|cata|wrath|wotlk|bcc|tbc|vanilla|\bera\b|mists|mop|-nolib|nolib""", RegexOption.IGNORE_CASE)
    private val REPO_URL = Regex("""^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?$""")

    fun parseRepo(text: String): GitHubRepo? =
        REPO_URL.matchEntire(text.trim())?.destructured?.let { (owner, repo) -> GitHubRepo(owner, repo) }

    /** Version numbers and separators stripped, so "Foo-2.1.14.zip" and "Foo-2.2.0.zip" compare equal. */
    internal fun assetShape(name: String) = name.lowercase().replace(Regex("""[\d._\-v]+"""), "")

    /**
     * A release zip. An asset shaped like [preferredAsset] (the one installed last time) wins, as in Obtainium;
     * otherwise one that isn't built for another game flavor.
     */
    fun parseLatest(json: String, preferredAsset: String? = null): RemoteFile {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: throw IOException("This release has no files")
        val zips = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".zip", ignoreCase = true) && it.optString("browser_download_url").startsWith("https://") }
        val pick = preferredAsset?.let { shape -> zips.firstOrNull { assetShape(it.optString("name")) == assetShape(shape) } }
            ?: zips.firstOrNull { !EXCLUDED_FLAVORS.containsMatchIn(it.optString("name")) } ?: zips.firstOrNull()
            ?: throw IOException("This release has no zip file")
        return RemoteFile(pick.getString("browser_download_url"), null, o.optString("tag_name"), pick.optString("name"))
    }

    fun latest(repo: GitHubRepo, preferredAsset: String? = null): RemoteFile =
        parseLatest(httpText("https://api.github.com/repos/${repo.owner}/${repo.repo}/releases/latest", github = true), preferredAsset)

    fun parseDetails(repoJson: String, releaseJson: String?): AddonDetails {
        val repo = JSONObject(repoJson)
        val release = releaseJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val notes = release?.optString("body")?.takeIf { it.isNotBlank() }
        val tag = release?.optString("tag_name")?.takeIf { it.isNotEmpty() }
        return AddonDetails(
            description = repo.optString("description").takeIf { it.isNotBlank() },
            changelog = notes?.let { n -> if (tag != null) "## $tag\n\n$n" else n },
            images = Markup.extractImageUrls(notes.orEmpty()),
            homepage = repo.optString("homepage").takeIf { it.startsWith("https://") } ?: repo.optString("html_url").takeIf { it.isNotEmpty() },
            markdown = true,
        )
    }

    fun details(repo: GitHubRepo): AddonDetails {
        val base = "https://api.github.com/repos/${repo.owner}/${repo.repo}"
        return parseDetails(httpText(base, github = true), runCatching { httpText("$base/releases/latest", github = true) }.getOrNull())
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

internal fun httpText(url: String, github: Boolean = false): String {
    val request = Request.Builder().url(url).header("User-Agent", "WoW-Forever-Android")
        .apply { if (github) header("Accept", "application/vnd.github+json") }.build()
    Net.http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("${response.request.url.host} answered ${response.code}")
        return response.body?.string() ?: throw IOException("${response.request.url.host} sent nothing")
    }
}
