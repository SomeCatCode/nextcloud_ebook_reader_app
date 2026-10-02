package com.somecatcode.ebookreader.data.account

import android.util.Base64
import com.somecatcode.ebookreader.data.db.AccountEntity
import com.somecatcode.ebookreader.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Room + encrypted credentials file per account (`<credentialsDir>/<accountId>.enc`, Base64 of
 * `iv || ciphertext || tag`, account id as AES-GCM associated data). [credentialsDir] must live in
 * `Context.noBackupFilesDir`.
 */
class AccountStoreImpl(
    private val db: AppDatabase,
    private val cipher: CredentialCipher,
    private val credentialsDir: File,
    /** Root of the local book files, `<root>/<accountId>/...` is deleted with the account. */
    private val booksRoot: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Called before anything is deleted: cancel running syncs/downloads of the account. */
    private val beforeRemove: suspend (String) -> Unit = {},
) : AccountStore {

    private val dao get() = db.accountDao()
    private val writeLock = Mutex()

    override val accounts: Flow<List<Account>> = dao.observeAll().map { rows -> rows.map { it.toAccount() } }

    override suspend fun list(): List<Account> = dao.getAll().map { it.toAccount() }

    override suspend fun get(accountId: String): Account? = dao.get(accountId)?.toAccount()

    override suspend fun add(account: NewAccount): Account = writeLock.withLock {
        val serverUrl = account.serverUrl.trim().trimEnd('/')
        dao.getAll().firstOrNull { it.serverUrl.equals(serverUrl, ignoreCase = true) && it.userId == account.userId }
            ?.let { throw AccountAlreadyExistsException(it.toAccount()) }
        val id = newId()
        // Encrypt first: if the keystore fails nothing was persisted.
        writeSecret(id, account.appPassword)
        val entity = AccountEntity(
            id = id,
            serverUrl = serverUrl,
            loginName = account.loginName,
            userId = account.userId,
            displayName = account.displayName,
            serverVersion = account.serverVersion,
            appVersion = account.appVersion,
            lastSyncCursor = null,
            lastSyncAt = null,
            createdAt = clock(),
        )
        try {
            dao.upsert(entity)
        } catch (e: Exception) {
            secretFile(id).delete()
            throw e
        }
        entity.toAccount()
    }

    override suspend fun updateCredentials(accountId: String, credentials: Credentials) = writeLock.withLock {
        val entity = dao.get(accountId) ?: return@withLock
        writeSecret(accountId, credentials.appPassword)
        if (entity.loginName != credentials.loginName) dao.upsert(entity.copy(loginName = credentials.loginName))
    }

    override suspend fun credentials(accountId: String): Credentials? = withContext(Dispatchers.IO) {
        val entity = dao.get(accountId) ?: return@withContext null
        val file = secretFile(accountId)
        if (!file.isFile) return@withContext null
        try {
            val encrypted = Base64.decode(file.readText(Charsets.US_ASCII).trim(), Base64.NO_WRAP)
            val plain = cipher.decrypt(encrypted, accountId.toByteArray(Charsets.UTF_8))
            Credentials(entity.loginName, String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            // Invalidated key, tampered or unreadable file: the account must sign in again.
            null
        }
    }

    override suspend fun remove(accountId: String) {
        beforeRemove(accountId)
        writeLock.withLock {
            withContext(Dispatchers.IO) { secretFile(accountId).delete() }
            dao.delete(accountId)
            withContext(Dispatchers.IO) { File(booksRoot(), accountId).deleteRecursively() }
        }
    }

    private suspend fun writeSecret(accountId: String, password: String) = withContext(Dispatchers.IO) {
        val encrypted = cipher.encrypt(password.toByteArray(Charsets.UTF_8), accountId.toByteArray(Charsets.UTF_8))
        credentialsDir.mkdirs()
        val target = secretFile(accountId)
        val tmp = File(credentialsDir, "$accountId.tmp")
        tmp.writeText(Base64.encodeToString(encrypted, Base64.NO_WRAP), Charsets.US_ASCII)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("Cannot store credentials")
            }
        }
    }

    private fun secretFile(accountId: String): File {
        require(accountId.matches(SAFE_ID)) { "Invalid account id" }
        return File(credentialsDir, "$accountId.enc")
    }

    private fun AccountEntity.toAccount() = Account(
        id = id,
        serverUrl = serverUrl,
        loginName = loginName,
        userId = userId,
        displayName = displayName,
        serverVersion = serverVersion,
        appVersion = appVersion,
        lastSyncAt = lastSyncAt,
    )

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
