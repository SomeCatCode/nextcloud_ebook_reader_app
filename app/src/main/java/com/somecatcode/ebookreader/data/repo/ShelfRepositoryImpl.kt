package com.somecatcode.ebookreader.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.BookQuery
import com.somecatcode.ebookreader.data.api.EbookApi
import com.somecatcode.ebookreader.data.api.FilterTerm
import com.somecatcode.ebookreader.data.api.FilterType
import com.somecatcode.ebookreader.data.api.SmartQueryDto
import com.somecatcode.ebookreader.data.api.SortKey
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.ShelfBookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Shelves of the server, mirrored in Room. The server has no membership endpoint: members of a manual
 * shelf are listed with `GET /books?include[]=shelf:<id>&sort=shelf` (same as the web app).
 */
internal object ShelfSync {
    private const val TAG = "ShelfSync"

    /** Shelf list plus the membership of every manual shelf, written in one transaction. */
    suspend fun refreshAll(db: AppDatabase, api: EbookApi, accountId: String) {
        val remote = api.shelves()
        val memberships = HashMap<Long, List<ShelfBookEntity>>()
        for (shelf in remote) {
            if (shelf.type != "manual") continue
            val members = fetchMembers(api, accountId, shelf.id)
            if (members.size != shelf.count) Log.w(TAG, "shelf ${shelf.id}: server count ${shelf.count}, listed ${members.size}")
            memberships[shelf.id] = members
        }
        db.withTransaction {
            db.shelfDao().upsertShelves(remote.map { it.toEntity(accountId) })
            db.shelfDao().deleteExcept(accountId, remote.map { it.id })
            for ((shelfId, members) in memberships) db.shelfDao().replaceMembers(accountId, shelfId, members)
        }
        Log.i(TAG, "account shelves: ${remote.size}, manual members: ${memberships.values.sumOf { it.size }}")
    }

    suspend fun refreshMembers(db: AppDatabase, api: EbookApi, accountId: String, shelfId: Long) {
        val members = fetchMembers(api, accountId, shelfId)
        db.withTransaction {
            if (db.shelfDao().get(accountId, shelfId) != null) db.shelfDao().replaceMembers(accountId, shelfId, members)
        }
    }

    suspend fun fetchMembers(api: EbookApi, accountId: String, shelfId: Long): List<ShelfBookEntity> {
        val out = ArrayList<ShelfBookEntity>()
        var offset = 0
        while (true) {
            val page = api.books(
                BookQuery(
                    include = listOf(FilterTerm(FilterType.SHELF, shelfId.toString())),
                    sort = SortKey.SHELF,
                    limit = 200,
                    offset = offset,
                ),
            )
            page.books.forEach { out += ShelfBookEntity(accountId, shelfId, it.fileId, out.size) }
            offset += page.books.size
            if (page.books.isEmpty() || offset >= page.total) break
        }
        return out
    }
}

class ShelfRepositoryImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
) : ShelfRepository {

    private suspend fun api(accountId: String) = apiFactory.forAccount(accountId)

    override suspend fun refresh(accountId: String) = ShelfSync.refreshAll(db, api(accountId), accountId)

    override suspend fun refreshMembers(key: ShelfKey) {
        val shelf = db.shelfDao().get(key.accountId, key.shelfId) ?: return
        if (shelf.isSmart()) return
        ShelfSync.refreshMembers(db, api(key.accountId), key.accountId, key.shelfId)
    }

    override suspend fun create(accountId: String, name: String, query: SmartQueryDto?): ShelfKey {
        val created = api(accountId).createShelf(name.trim(), query?.withoutShelfTerms())
        db.shelfDao().upsertShelves(listOf(created.toEntity(accountId)))
        return ShelfKey(accountId, created.id)
    }

    override suspend fun rename(key: ShelfKey, name: String) {
        val updated = api(key.accountId).updateShelf(key.shelfId, name = name.trim())
        db.shelfDao().upsertShelves(listOf(updated.toEntity(key.accountId)))
    }

    override suspend fun updateQuery(key: ShelfKey, query: SmartQueryDto) {
        val updated = api(key.accountId).updateShelf(key.shelfId, query = query.withoutShelfTerms())
        db.shelfDao().upsertShelves(listOf(updated.toEntity(key.accountId)))
    }

    override suspend fun delete(key: ShelfKey) {
        api(key.accountId).deleteShelf(key.shelfId)
        db.shelfDao().deleteOne(key.accountId, key.shelfId)
    }

    override suspend fun move(key: ShelfKey, delta: Int) {
        val ordered = db.shelfDao().getAll(key.accountId).sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() })).toMutableList()
        val from = ordered.indexOfFirst { it.id == key.shelfId }
        val to = from + delta
        if (from < 0 || to !in ordered.indices) return
        ordered.add(to, ordered.removeAt(from))
        val api = api(key.accountId)
        val changed = ordered.mapIndexedNotNull { index, shelf -> if (shelf.sortOrder != index) shelf.copy(sortOrder = index) else null }
        for (shelf in changed) api.updateShelf(shelf.id, sortOrder = shelf.sortOrder)
        db.shelfDao().upsertShelves(changed)
    }

    override suspend fun addBooks(key: ShelfKey, fileIds: List<Long>) {
        if (fileIds.isEmpty()) return
        api(key.accountId).addToShelf(key.shelfId, fileIds)
        refreshAfterChange(key)
    }

    override suspend fun removeBooks(key: ShelfKey, fileIds: List<Long>) {
        if (fileIds.isEmpty()) return
        api(key.accountId).removeFromShelf(key.shelfId, fileIds)
        refreshAfterChange(key)
    }

    /** Count and covers change on the server; reload the list and this shelf's members. */
    private suspend fun refreshAfterChange(key: ShelfKey) {
        val api = api(key.accountId)
        val remote = api.shelves()
        val members = ShelfSync.fetchMembers(api, key.accountId, key.shelfId)
        db.withTransaction {
            db.shelfDao().upsertShelves(remote.map { it.toEntity(key.accountId) })
            db.shelfDao().replaceMembers(key.accountId, key.shelfId, members)
        }
    }

    override fun shelvesOf(book: BookKey): Flow<Set<Long>> =
        db.shelfDao().observeAllMembers(listOf(book.accountId))
            .map { rows -> rows.filter { it.fileId == book.fileId }.map { it.shelfId }.toSet() }
            .distinctUntilChanged()
}

/** Smart shelves must not reference shelves (server rule). */
internal fun SmartQueryDto.withoutShelfTerms(): SmartQueryDto {
    val isShelf = { t: String -> t.trim().startsWith("shelf:", ignoreCase = true) }
    return copy(include = include.filterNot(isShelf), exclude = exclude.filterNot(isShelf))
}
