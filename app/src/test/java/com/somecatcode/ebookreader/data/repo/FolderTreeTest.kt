package com.somecatcode.ebookreader.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    private val paths = listOf(
        "/Top.epub",
        "/Books/a.epub",
        "/Books/b.epub",
        "/Books/Comics/Saga/1.cbz",
        "/Books/Comics/Saga/2.cbz",
        "/Books/Comics/x.cbz",
        "/Shared/Bob/Sub/z.epub",
        "/books2/c.epub",
    )

    @Test
    fun normalizesFolderPaths() {
        assertEquals("", FolderTree.normalize(null))
        assertEquals("", FolderTree.normalize("/"))
        assertEquals("/Books", FolderTree.normalize("Books/"))
        assertEquals("/Books/Comics", FolderTree.normalize("//Books//Comics/"))
        assertEquals("/Books/Comics", FolderTree.folderOf("/Books/Comics/x.cbz"))
        assertEquals("", FolderTree.folderOf("/Top.epub"))
        assertEquals("", FolderTree.folderOf("Top.epub"))
    }

    @Test
    fun parentAndBreadcrumb() {
        assertNull(FolderTree.parent(""))
        assertEquals("", FolderTree.parent("/Books"))
        assertEquals("/Books", FolderTree.parent("/Books/Comics"))
        assertEquals("Comics", FolderTree.name("/Books/Comics"))
        assertEquals(listOf("Books" to "/Books", "Comics" to "/Books/Comics"), FolderTree.breadcrumb("/Books/Comics"))
        assertTrue(FolderTree.breadcrumb("").isEmpty())
    }

    @Test
    fun inFolderMatchesDirectChildrenOrTheWholeSubtree() {
        assertTrue(FolderTree.inFolder("/Books/a.epub", "/Books", false))
        assertFalse(FolderTree.inFolder("/Books/Comics/x.cbz", "/Books", false))
        assertTrue(FolderTree.inFolder("/Books/Comics/x.cbz", "/Books", true))
        assertFalse("a sibling with the same prefix is not a subfolder", FolderTree.inFolder("/books2/c.epub", "/books", true))
        assertFalse(FolderTree.inFolder("/Booksx/c.epub", "/Books", true))
        assertTrue(FolderTree.inFolder("/Top.epub", "", false))
        assertFalse(FolderTree.inFolder("/Books/a.epub", "", false))
        assertTrue(FolderTree.inFolder("/Books/a.epub", "", true))
    }

    @Test
    fun listsSubfoldersWithDirectAndTotalCounts() {
        val root = FolderTree.list(paths, "", false) { it }
        assertEquals(listOf("Books", "books2", "Shared"), root.subfolders.map { it.name })
        assertEquals(listOf("/Top.epub"), root.items)
        val books = root.subfolders.first()
        assertEquals(2, books.bookCount)
        assertEquals(5, books.totalCount)
        assertEquals(paths.size, root.totalCount)

        val inBooks = FolderTree.list(paths, "/Books/", false) { it }
        assertEquals("/Books", inBooks.folder)
        assertEquals(listOf("/Books/Comics"), inBooks.subfolders.map { it.path })
        assertEquals(1, inBooks.subfolders.single().bookCount)
        assertEquals(3, inBooks.subfolders.single().totalCount)
        assertEquals(listOf("/Books/a.epub", "/Books/b.epub"), inBooks.items)

        val recursive = FolderTree.list(paths, "/Books", true) { it }
        assertEquals(5, recursive.items.size)
        assertEquals(5, recursive.totalCount)
    }

    @Test
    fun foldersWithoutDirectBooksStillAppearAsIntermediateLevels() {
        val shared = FolderTree.list(paths, "/Shared", false) { it }
        assertEquals(listOf("/Shared/Bob"), shared.subfolders.map { it.path })
        assertEquals(0, shared.subfolders.single().bookCount)
        assertEquals(1, shared.subfolders.single().totalCount)
        assertTrue(shared.items.isEmpty())
    }

    @Test
    fun unknownFolderIsEmpty() {
        val none = FolderTree.list(paths, "/Nope", true) { it }
        assertTrue(none.subfolders.isEmpty())
        assertTrue(none.items.isEmpty())
    }

    @Test
    fun sharedFilterDirections() {
        assertTrue(SharedFilters.matches(SharedFilter.ANY, incoming = true, outgoing = false))
        assertTrue(SharedFilters.matches(SharedFilter.ANY, incoming = false, outgoing = true))
        assertFalse(SharedFilters.matches(SharedFilter.ANY, incoming = false, outgoing = false))
        assertTrue(SharedFilters.matches(SharedFilter.INCOMING, incoming = true, outgoing = false))
        assertFalse(SharedFilters.matches(SharedFilter.INCOMING, incoming = false, outgoing = true))
        assertTrue(SharedFilters.matches(SharedFilter.OUTGOING, incoming = false, outgoing = true))
        assertFalse(SharedFilters.matches(SharedFilter.OUTGOING, incoming = true, outgoing = false))
    }
}
