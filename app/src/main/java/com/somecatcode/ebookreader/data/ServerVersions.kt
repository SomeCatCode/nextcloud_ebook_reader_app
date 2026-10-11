package com.somecatcode.ebookreader.data

/**
 * Versions of the E-Book Reader server app and the features that need them. The server reports its
 * version in the capabilities (`ebookreader.version`) since 0.8.0; older servers send none, so an
 * unknown version means "older than 0.8.0".
 */
object ServerVersions {
    /** Oldest server app the app works with at all (probed at login, see `checkCompatibility`). */
    const val MIN_SUPPORTED = "0.5.0"

    /** Server app this app version is built and tested against; older servers get a hint. */
    const val RECOMMENDED = "0.8.0"

    /** First server version that reports its version in the capabilities. */
    const val REPORTS_VERSION = "0.8.0"

    /**
     * Compares `x.y.z` versions numerically; a pre-release suffix (`0.8.0-rc.1`) sorts before the release.
     * Unparsable parts count as 0.
     */
    fun compare(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, String?> {
            val core = v.trim().removePrefix("v").substringBefore('-')
            val pre = v.substringAfter('-', "").takeIf { it.isNotEmpty() }
            return core.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } } to pre
        }
        val (x, xp) = parts(a)
        val (y, yp) = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            xp == yp -> 0
            xp == null -> 1
            yp == null -> -1
            else -> xp.compareTo(yp)
        }
    }

    /** Whether a server with [version] (null = unknown, i.e. older than [REPORTS_VERSION]) has at least [min]. */
    fun isAtLeast(version: String?, min: String): Boolean = when {
        version != null -> compare(version, min) >= 0
        // unknown version: the server is older than the first version that reports one
        else -> compare(min, REPORTS_VERSION) < 0
    }

    fun isOutdated(version: String?): Boolean = !isAtLeast(version, RECOMMENDED)
}

/** Server-side features with the server app version they need. */
enum class ServerFeature(val since: String) {
    SHELF_MANAGEMENT("0.5.0"),
    ANNOTATIONS("0.6.0"),
    STATUS_PROGRESS_COUPLING("0.7.0"),
    VERSION_REPORTING("0.8.0"),
    BOOK_FLAGS("0.8.0"),
    SHARING("0.8.0"),

    /** `shared` filter of `GET /books` and the `sharedOut` book field: "Shared" view, share badges on own books. */
    SHARED_VIEW("0.10.0"),

    /** Book paths and folder shares (`ebookreader.features` contains `folders`): "Folders" view. */
    FOLDERS_VIEW("0.10.0");

    fun availableOn(serverAppVersion: String?): Boolean = ServerVersions.isAtLeast(serverAppVersion, since)
}
