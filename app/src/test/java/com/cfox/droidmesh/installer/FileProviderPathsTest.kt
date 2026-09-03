package com.cfox.droidmesh.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [PROGRAMMATIC] gitea#41 L2: FileProvider's `file_paths.xml` must grant access to nothing
 * broader than the exact subdirectory `ApkDownloader.downloadApk()` writes into --
 * `context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`, i.e. the resource's
 * `<external-files-path path="Download" />` entry -- since that's the only file `dispatchInstall`
 * (`PackageInstallerDispatcher`) ever resolves a `content://` URI for. A root-level `"."` grant
 * on external-files, internal cache, or external cache would let this provider's authority mint
 * a `content://` URI over the *entire* app sandbox, not just downloaded APKs.
 */
class FileProviderPathsTest {

    private fun parsePaths(): List<Triple<String, String, String>> {
        val file = File("src/main/res/xml/file_paths.xml")
        assertTrue("file_paths.xml must exist", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val entries = mutableListOf<Triple<String, String, String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val el = node as org.w3c.dom.Element
                val name = el.getAttribute("name")
                val path = el.getAttribute("path")
                entries.add(Triple(el.tagName, name, path))
            }
        }
        return entries
    }

    @Test
    fun testFileProviderGrantsOnlyTheDownloadsSubdirectoryApkDownloaderWritesInto() {
        val entries = parsePaths()

        // Exactly one grant: external-files-path pointing at the Download subdirectory that
        // ApkDownloader.downloadApk() actually writes into.
        assertEquals(
            "file_paths.xml must grant exactly one path -- the ApkDownloader Download subdirectory",
            1,
            entries.size
        )
        val (tag, name, path) = entries.single()
        assertEquals("external-files-path", tag)
        assertEquals("external_files_downloads", name)
        assertEquals("Download", path)
    }

    @Test
    fun testFileProviderGrantsNoRootLevelOrCachePaths() {
        val entries = parsePaths()

        // Negative case: no entry may grant a root-level "." path (the whole app sandbox), and
        // no cache-path/external-cache-path entries may exist at all -- ApkDownloader never
        // writes APKs to cache, so FileProvider has no legitimate reason to expose it.
        assertTrue(
            "no entry may grant the root-level \".\" path",
            entries.none { it.third == "." }
        )
        assertTrue(
            "no cache-path or external-cache-path entries may exist",
            entries.none { it.first == "cache-path" || it.first == "external-cache-path" }
        )
    }
}
