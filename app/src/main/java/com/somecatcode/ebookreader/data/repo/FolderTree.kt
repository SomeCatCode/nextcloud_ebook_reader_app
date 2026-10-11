package com.somecatcode.ebookreader.data.repo

/** Subfolder of the shown folder; [bookCount] = books directly inside, [totalCount] = including all subfolders. */
data class FolderNode(val path: String, val name: String, val bookCount: Int, val totalCount: Int)

/** One level of the folder structure: the sub-folders and the books of [folder]. */
data class FolderListing<T>(
    /** Normalised path of the shown folder, "" = top level. */
    val folder: String,
    val subfolders: List<FolderNode>,
    /** Books directly in [folder], or the whole subtree when listed recursively. */
    val items: List<T>,
    /** Books in [folder] including all subfolders (the number a recursive listing shows). */
    val totalCount: Int,
)

/**
 * Folder structure computed locally from the synced book paths (user-relative server paths such as
 * `/Books/Comics/Saga/v01.cbz`; the folder of that book is `/Books/Comics/Saga`). Folder paths have a
 * leading slash and no trailing slash; the top level is the empty string.
 */
object FolderTree {

    /** `/Books/` -> `/Books`, `Books` -> `/Books`, `/` -> "" (top level). */
    fun normalize(folder: String?): String {
        val parts = folder.orEmpty().split('/').filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
    }

    /** Folder containing the file [path]. */
    fun folderOf(path: String): String = normalize(path.substringBeforeLast('/', ""))

    /** Parent of [folder]; null for the top level. */
    fun parent(folder: String): String? {
        val f = normalize(folder)
        return if (f.isEmpty()) null else f.substringBeforeLast('/')
    }

    fun name(folder: String): String = normalize(folder).substringAfterLast('/')

    /** Path segments from the top level down to [folder] as (name, path); empty for the top level. */
    fun breadcrumb(folder: String): List<Pair<String, String>> {
        val parts = normalize(folder).split('/').filter { it.isNotEmpty() }
        return parts.indices.map { i -> parts[i] to "/" + parts.take(i + 1).joinToString("/") }
    }

    /** The file [path] lies directly in [folder] ([recursive]: or in one of its subfolders). */
    fun inFolder(path: String, folder: String, recursive: Boolean): Boolean {
        val dir = folderOf(path)
        val f = normalize(folder)
        return if (recursive) f.isEmpty() || dir == f || dir.startsWith("$f/") else dir == f
    }

    /** Lists [current]: its direct sub-folders with counts plus the items directly in it (or in the whole subtree). */
    fun <T> list(items: List<T>, current: String, recursive: Boolean, pathOf: (T) -> String): FolderListing<T> {
        val cur = normalize(current)
        val prefix = if (cur.isEmpty()) "/" else "$cur/"
        val direct = ArrayList<T>()
        val subtree = ArrayList<T>()
        val direct0 = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        for (item in items) {
            val dir = folderOf(pathOf(item))
            if (dir == cur) {
                direct += item
                subtree += item
            } else if (dir.startsWith(prefix)) {
                subtree += item
                val child = prefix + dir.substring(prefix.length).substringBefore('/')
                total[child] = (total[child] ?: 0) + 1
                if (dir == child) direct0[child] = (direct0[child] ?: 0) + 1
            }
        }
        val nodes = total.map { (path, count) -> FolderNode(path, name(path), direct0[path] ?: 0, count) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return FolderListing(cur, nodes, if (recursive) subtree else direct, subtree.size)
    }
}

/** Matching of the `shared` filter (same meaning as the server parameter). */
object SharedFilters {
    fun matches(mode: SharedFilter, incoming: Boolean, outgoing: Boolean): Boolean = when (mode) {
        SharedFilter.INCOMING -> incoming
        SharedFilter.OUTGOING -> outgoing
        SharedFilter.ANY -> incoming || outgoing
    }
}
