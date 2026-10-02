package com.somecatcode.ebookreader.ui.navigation

import android.net.Uri

/**
 * Navigation routes. Book-scoped routes carry the account id and the Nextcloud file id
 * (the primary key of a book is accountId + fileId).
 */
object Routes {
    const val ACCOUNTS = "accounts"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_FILE_ID = "fileId"
    const val ARG_SHELF_ID = "shelfId"
    const val ARG_SERIES_NAME = "seriesName"

    const val BOOK_DETAIL = "book/{$ARG_ACCOUNT_ID}/{$ARG_FILE_ID}"
    const val READER = "reader/{$ARG_ACCOUNT_ID}/{$ARG_FILE_ID}"
    const val SHELF = "shelf/{$ARG_ACCOUNT_ID}/{$ARG_SHELF_ID}"
    const val SERIES = "series/{$ARG_ACCOUNT_ID}/{$ARG_SERIES_NAME}"

    /** Deep link used by download notifications (PendingIntent with ACTION_VIEW on MainActivity). */
    const val DOWNLOADS_DEEP_LINK = "ebookreader://downloads"

    fun bookDetail(accountId: String, fileId: Long) = "book/$accountId/$fileId"
    fun reader(accountId: String, fileId: Long) = "reader/$accountId/$fileId"
    fun shelf(accountId: String, shelfId: Long) = "shelf/$accountId/$shelfId"
    fun series(accountId: String, name: String) = "series/$accountId/${Uri.encode(name)}"
}
