package com.somecatcode.ebookreader.ui.navigation

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

    const val BOOK_DETAIL = "book/{$ARG_ACCOUNT_ID}/{$ARG_FILE_ID}"
    const val READER = "reader/{$ARG_ACCOUNT_ID}/{$ARG_FILE_ID}"

    fun bookDetail(accountId: String, fileId: Long) = "book/$accountId/$fileId"
    fun reader(accountId: String, fileId: Long) = "reader/$accountId/$fileId"
}
