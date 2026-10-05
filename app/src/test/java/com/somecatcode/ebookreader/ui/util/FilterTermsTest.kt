package com.somecatcode.ebookreader.ui.util

import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterTermsTest {

    @Test
    fun cycleGoesIncludeExcludeOff() {
        var f = LibraryFilter()
        f = f.cycle("genre:Fantasy")
        assertEquals(listOf("genre:Fantasy"), f.include)
        f = f.cycle("genre:fantasy") // case-insensitive
        assertEquals(emptyList<String>(), f.include)
        assertEquals(listOf("genre:fantasy"), f.exclude)
        f = f.cycle("genre:Fantasy")
        assertEquals(LibraryFilter(), f)
    }

    @Test
    fun flipSwapsIncludeAndExclude() {
        val f = LibraryFilter(include = listOf("tag:x")).flip("tag:x")
        assertEquals(listOf("tag:x"), f.exclude)
        assertEquals(TermState.INCLUDE, f.flip("tag:x").stateOf("tag:x"))
    }

    @Test
    fun treeAddsImplicitParentsAndSumsCounts() {
        val roots = buildTagTree(
            listOf(FacetCount("Fantasy/Epic", 2), FacetCount("Fantasy/Urban", 1), FacetCount("Sci-Fi", 4), FacetCount("Vol 10", 1), FacetCount("Vol 9", 1)),
        )
        assertEquals(listOf("Fantasy", "Sci-Fi", "Vol 9", "Vol 10"), roots.map { it.label })
        val fantasy = roots.first()
        assertEquals(0, fantasy.ownCount)
        assertEquals(3, fantasy.totalCount)
        assertEquals("Fantasy/*", fantasy.filterName())
        assertEquals("Fantasy/Epic", fantasy.children.first().filterName())
    }

    @Test
    fun flattenShowsChildrenOnlyWhenExpandedOrSearching() {
        val roots = buildTagTree(listOf(FacetCount("Fantasy/Epic", 2), FacetCount("Horror", 1)))
        assertEquals(listOf("Fantasy", "Horror"), flattenTree(roots, emptySet()).map { it.label })
        assertEquals(listOf("Fantasy", "Epic", "Horror"), flattenTree(roots, setOf("fantasy")).map { it.label })
        assertEquals(listOf("Fantasy", "Epic"), flattenTree(roots, emptySet(), "epi").map { it.label })
    }
}
