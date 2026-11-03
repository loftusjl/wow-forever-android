package app.gamenative.addons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProvidersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val wowiList = """[
        {"UID":"3826","UICATID":"19","UIVersion":"11.0.2.3","UIName":"CTMod","UIAuthorName":"DahkCeles",
         "UIFileInfoURL":"https:\/\/www.wowinterface.com\/downloads\/info3826-CTMod.html","UIDownloadTotal":"598624","UIDownloadMonthly":"41",
         "UICompatibility":[{"version":"11.0.2","name":"The War Within"},{"version":"1.15.2","name":"Classic"}],"UIDir":["CT_Core","CT_Library"]},
        {"UID":"9","UIVersion":"2.0","UIName":"Midnight Thing","UIAuthorName":"Someone","UIDownloadTotal":"5","UIDownloadMonthly":"x",
         "UICompatibility":[{"version":"12.0.0","name":"Midnight"}],"UIDir":null},
        {"UIName":"No id"}
    ]"""

    @Test
    fun wowInterface_parsesCatalogAndFlagsMidnight() {
        val list = WowInterfaceProvider.parseFileList(wowiList)
        assertEquals(2, list.size)
        val ct = list[0]
        assertEquals("wowinterface:3826", ct.key)
        assertEquals(listOf("CT_Core", "CT_Library"), ct.folders)
        assertEquals(41L, ct.downloadsMonthly)
        assertFalse(ct.isMidnight)
        assertTrue(list[1].isMidnight)
        assertEquals(0L, list[1].downloadsMonthly)
        assertTrue(list[1].folders.isEmpty())
    }

    @Test
    fun wowInterface_parsesFileDetails() {
        val file = WowInterfaceProvider.parseFileDetails("""[{"UID":"3826","UIVersion":"11.0.2.3","UIMD5":"801d2daf73ad195c02c58d50be7f1b22","UIDownload":"https:\/\/cdn.wowinterface.com\/downloads\/getfile.php?id=3826&d=1&minion"}]""")
        assertEquals("https://cdn.wowinterface.com/downloads/getfile.php?id=3826&d=1&minion", file.url)
        assertEquals("801d2daf73ad195c02c58d50be7f1b22", file.md5)
        assertEquals("11.0.2.3", file.version)
        try {
            WowInterfaceProvider.parseFileDetails("[]")
            fail("expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun tukui_parsesAddonsWithHttpsDownloads() {
        val list = TukuiProvider.parse("""[
            {"id":-2,"slug":"elvui","author":"Elv","name":"ElvUI","url":"https://api.tukui.org/v1/download/elvui/abc","version":"16.00","patch":["12.1.0","1.15.9"],"web_url":"https://tukui.org/elvui","small_desc":"A UI"},
            {"slug":"insecure","name":"X","url":"http://example.com/x.zip","version":"1"}
        ]""")
        assertEquals(1, list.size)
        assertEquals("tukui:elvui", list[0].key)
        assertTrue(list[0].isMidnight)
        assertEquals("https://api.tukui.org/v1/download/elvui/abc", list[0].downloadUrl)
    }

    @Test
    fun gitHub_parsesRepoUrls() {
        assertEquals(GitHubRepo("Stanzilla", "AdvancedInterfaceOptions"), GitHubProvider.parseRepo("https://github.com/Stanzilla/AdvancedInterfaceOptions"))
        assertEquals(GitHubRepo("a", "b"), GitHubProvider.parseRepo("github.com/a/b/releases/tag/v1"))
        assertEquals(GitHubRepo("a", "b"), GitHubProvider.parseRepo(" https://github.com/a/b.git "))
        assertNull(GitHubProvider.parseRepo("https://example.com/a/b.zip"))
        assertNull(GitHubProvider.parseRepo("https://github.com/onlyowner"))
    }

    @Test
    fun gitHub_prefersTheFlavorNeutralZip() {
        val json = """{"tag_name":"2.1.14","assets":[
            {"name":"Foo-2.1.14-cata.zip","browser_download_url":"https://github.com/a/b/releases/download/2.1.14/Foo-2.1.14-cata.zip"},
            {"name":"Foo-2.1.14.zip","browser_download_url":"https://github.com/a/b/releases/download/2.1.14/Foo-2.1.14.zip"},
            {"name":"release.json","browser_download_url":"https://github.com/a/b/releases/download/2.1.14/release.json"}]}"""
        val file = GitHubProvider.parseLatest(json)
        assertEquals("https://github.com/a/b/releases/download/2.1.14/Foo-2.1.14.zip", file.url)
        assertEquals("2.1.14", file.version)
        assertNull(file.md5)
    }

    @Test
    fun gitHub_fallsBackToAnyZip_andRejectsNone() {
        val onlyClassic = """{"tag_name":"v1","assets":[{"name":"Foo-classic.zip","browser_download_url":"https://x/Foo-classic.zip"}]}"""
        assertEquals("https://x/Foo-classic.zip", GitHubProvider.parseLatest(onlyClassic).url)
        try {
            GitHubProvider.parseLatest("""{"tag_name":"v1","assets":[{"name":"src.tar.gz","browser_download_url":"https://x/src.tar.gz"}]}""")
            fail("expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun sources_recordReplacesPriorOwnerOfTheSameFolders() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        AddonSources.record(root, SourceRecord(Provider.WOWINTERFACE, "1", "Old", "1.0", listOf("Foo", "Bar")))
        AddonSources.record(root, SourceRecord(Provider.TUKUI, "foo", "New", "2.0", listOf("Foo")))
        val all = AddonSources.load(root)
        assertEquals(setOf("tukui:foo", "wowinterface:1"), all.map { "${it.provider.key}:${it.id}" }.toSet())
        assertEquals(listOf("Bar"), all.first { it.provider == Provider.WOWINTERFACE }.folders)
    }

    @Test
    fun sources_pruneDropsRemovedAddons() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        AddonSources.record(root, SourceRecord(Provider.WOWINTERFACE, "1", "A", "1", listOf("A1", "A2")))
        AddonSources.record(root, SourceRecord(Provider.TUKUI, "b", "B", "1", listOf("B1")))
        AddonSources.prune(root, setOf("A1"))
        val all = AddonSources.load(root)
        assertEquals(1, all.size)
        assertEquals(listOf("A1"), all[0].folders)
    }

    @Test
    fun sources_loadSurvivesGarbage() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        File(root, "_forever_/Interface/.addon-sources.json").writeText("not json")
        assertTrue(AddonSources.load(root).isEmpty())
    }

    @Test
    fun md5_matchesKnownValue() {
        val f = File(tmp.root, "x").also { it.writeText("hello") }
        assertEquals("5d41402abc4b2a76b9719d911017c592", AddonInstaller.md5(f))
    }

    @Test
    fun wowInterface_parsesDetailsWithImagesFromEverywhere() {
        val d = WowInterfaceProvider.parseDetails(
            """[{"UIDescription":"Nice [B]addon[/B] [IMG]https://cdn.test/inline.png[/IMG]","UIChangeLog":"# Changelog\r\n- fixed","UIIMGs":["https://cdn.test/a.png","http://insecure.test/b.png"]}]""",
            listOf("https://cdn.test/a.png", "https://cdn.test/fallback.png"), "https://www.wowinterface.com/x",
        )
        assertEquals(listOf("https://cdn.test/a.png", "https://cdn.test/fallback.png", "https://cdn.test/inline.png"), d.images)
        assertTrue(d.changelog!!.contains("fixed"))
        assertEquals("https://www.wowinterface.com/x", d.homepage)
        assertFalse(d.markdown)
    }

    @Test
    fun tukui_parsesDetailsAndTruncatesHugeChangelogsAtALineBreak() {
        val json = """{"desc":"## About\n**ElvUI** rocks","small_desc":"short","screenshot_url":"https://s.test/1.jpg","gallery_url":["https://s.test/2.jpg"],"web_url":"https://tukui.org/elvui"}"""
        val long = (1..2000).joinToString("\n") { "* change number $it" }
        val d = TukuiProvider.parseDetails(json, long)
        assertEquals(listOf("https://s.test/1.jpg", "https://s.test/2.jpg"), d.images)
        assertTrue(d.markdown)
        assertTrue(d.changelog!!.length < 8100 && d.changelog!!.endsWith("…"))
        assertEquals("short", TukuiProvider.parseDetails("""{"small_desc":"short"}""", null).description)
    }

    @Test
    fun gitHub_parsesDetails() {
        val d = GitHubProvider.parseDetails(
            """{"description":"Restores options","homepage":"https://www.curseforge.com/wow/addons/x","html_url":"https://github.com/a/b"}""",
            """{"tag_name":"2.1.14","body":"- fixed ![s](https://i.test/s.png)"}""",
        )
        assertEquals("Restores options", d.description)
        assertEquals("## 2.1.14\n\n- fixed ![s](https://i.test/s.png)", d.changelog)
        assertEquals(listOf("https://i.test/s.png"), d.images)
        assertEquals("https://www.curseforge.com/wow/addons/x", d.homepage)
        assertEquals("https://github.com/a/b", GitHubProvider.parseDetails("""{"html_url":"https://github.com/a/b","homepage":""}""", null).homepage)
    }

    @Test
    fun gitHub_prefersTheSameKindOfAssetAsLastTime() {
        val json = """{"tag_name":"3.0.0","assets":[
            {"name":"Foo-3.0.0.zip","browser_download_url":"https://x/Foo-3.0.0.zip"},
            {"name":"Foo-3.0.0-mainline.zip","browser_download_url":"https://x/Foo-3.0.0-mainline.zip"}]}"""
        assertEquals("Foo-3.0.0.zip", GitHubProvider.parseLatest(json).assetName)
        assertEquals("Foo-3.0.0-mainline.zip", GitHubProvider.parseLatest(json, preferredAsset = "Foo-2.9.1-mainline.zip").assetName)
        assertEquals("Foo-3.0.0.zip", GitHubProvider.parseLatest(json, preferredAsset = "Gone-1.0.zip").assetName)
    }

    @Test
    fun fingerprint_prefersEtagThenModifiedThenSize() {
        assertEquals("etag:\"abc\"", AddonInstaller.fingerprintOf("\"abc\"", "Mon", 5))
        assertEquals("modified:Mon", AddonInstaller.fingerprintOf(null, "Mon", 5))
        assertEquals("size:5", AddonInstaller.fingerprintOf("", null, 5))
        assertNull(AddonInstaller.fingerprintOf(null, null, 0))
    }

    @Test
    fun sources_rememberTheAssetAndTrackUrlRecords() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        AddonSources.record(root, SourceRecord(Provider.GITHUB, "a/b", "b", "1.0", listOf("B"), "B-1.0.zip"))
        AddonSources.record(root, SourceRecord(Provider.URL, "https://x.test/addon.zip", "Addon", "etag:\"1\"", listOf("Addon")))
        val all = AddonSources.load(root)
        assertEquals("B-1.0.zip", all.first { it.provider == Provider.GITHUB }.assetName)
        assertNull(all.first { it.provider == Provider.URL }.assetName)
        assertEquals(Provider.URL, Provider.of("url"))
    }
}
