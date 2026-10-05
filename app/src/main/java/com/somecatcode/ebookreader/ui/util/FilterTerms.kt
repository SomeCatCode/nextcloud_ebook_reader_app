package com.somecatcode.ebookreader.ui.util

import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryFilter

/* Filter terms (`type:name`) as used by the web app and the server; helpers for the filter UI. */

enum class TermState { OFF, INCLUDE, EXCLUDE }

fun term(type: String, name: String) = "$type:$name"

fun termType(term: String): String = term.substringBefore(':').lowercase()

fun termName(term: String): String = term.substringAfter(':')

private fun sameTerm(a: String, b: String) = a.equals(b, ignoreCase = true)

fun LibraryFilter.stateOf(term: String): TermState = when {
    include.any { sameTerm(it, term) } -> TermState.INCLUDE
    exclude.any { sameTerm(it, term) } -> TermState.EXCLUDE
    else -> TermState.OFF
}

fun LibraryFilter.withTerm(term: String, state: TermState): LibraryFilter {
    val inc = include.filterNot { sameTerm(it, term) }
    val exc = exclude.filterNot { sameTerm(it, term) }
    return when (state) {
        TermState.OFF -> copy(include = inc, exclude = exc)
        TermState.INCLUDE -> copy(include = inc + term, exclude = exc)
        TermState.EXCLUDE -> copy(include = inc, exclude = exc + term)
    }
}

/** off → include → exclude → off (like the web navigation). */
fun LibraryFilter.cycle(term: String): LibraryFilter = withTerm(
    term,
    when (stateOf(term)) {
        TermState.OFF -> TermState.INCLUDE
        TermState.INCLUDE -> TermState.EXCLUDE
        TermState.EXCLUDE -> TermState.OFF
    },
)

/** include ⇄ exclude for an active chip. */
fun LibraryFilter.flip(term: String): LibraryFilter =
    withTerm(term, if (stateOf(term) == TermState.INCLUDE) TermState.EXCLUDE else TermState.INCLUDE)

fun LibraryFilter.hasTerms(): Boolean = include.isNotEmpty() || exclude.isNotEmpty()

/** One node of a genre/tag tree ("Fantasy/Epic" lives below "Fantasy"). */
data class TagNode(
    /** Last path segment. */
    val label: String,
    /** Full normalised path. */
    val path: String,
    val depth: Int,
    /** Books with exactly this name. */
    val ownCount: Int,
    /** Own count plus all descendants (approximate: a book can carry several sub-entries). */
    val totalCount: Int,
    val children: List<TagNode>,
) {
    val hasChildren: Boolean get() = children.isNotEmpty()

    /** Subtree term for parents (path + slash-star), exact name for leaves. */
    fun filterName(): String = if (hasChildren) "$path/*" else path
}

private val naturalOrder: Comparator<String> = Comparator { a, b ->
    val chunk = Regex("""\d+|\D+""")
    val xa = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val xb = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(xa.size, xb.size)) {
        val p = xa[i]
        val q = xb[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) {
            p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
        } else {
            p.compareTo(q)
        }
        if (c != 0) return@Comparator c
    }
    xa.size.compareTo(xb.size)
}

/** Builds the tree of hierarchical names; implicit parents get a node with own count 0. */
fun buildTagTree(facets: List<FacetCount>): List<TagNode> {
    class Mutable(val label: String, val path: String, val depth: Int) {
        var own = 0
        val children = LinkedHashMap<String, Mutable>()
    }
    val roots = LinkedHashMap<String, Mutable>()
    for (facet in facets) {
        val parts = facet.name.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        var level = roots
        var node: Mutable? = null
        parts.forEachIndexed { i, part ->
            val path = parts.subList(0, i + 1).joinToString("/")
            node = level.getOrPut(part.lowercase()) { Mutable(part, path, i) }
            level = node!!.children
        }
        node!!.own += facet.count
    }
    fun freeze(m: Mutable): TagNode {
        val kids = m.children.values.map(::freeze).sortedWith(compareBy(naturalOrder) { it.label })
        return TagNode(m.label, m.path, m.depth, m.own, m.own + kids.sumOf { it.totalCount }, kids)
    }
    return roots.values.map(::freeze).sortedWith(compareBy(naturalOrder) { it.label })
}

/** Visible rows of the tree: children of expanded nodes only; with [query] every match and its ancestors. */
fun flattenTree(roots: List<TagNode>, expanded: Set<String>, query: String = ""): List<TagNode> {
    val q = query.trim().lowercase()
    val out = ArrayList<TagNode>()
    fun matches(n: TagNode): Boolean = n.path.lowercase().contains(q) || n.children.any(::matches)
    fun walk(nodes: List<TagNode>) {
        for (n in nodes) {
            if (q.isNotEmpty() && !matches(n)) continue
            out += n
            if (q.isNotEmpty() || n.path.lowercase() in expanded) walk(n.children)
        }
    }
    walk(roots)
    return out
}
