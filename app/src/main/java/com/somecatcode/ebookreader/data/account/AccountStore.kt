package com.somecatcode.ebookreader.data.account

import kotlinx.coroutines.flow.Flow

/** Credentials of one account. The app password never leaves the process except in `Authorization` headers. */
data class Credentials(val loginName: String, val appPassword: String) {
    /** Never prints the password (logs, crash reports). */
    override fun toString(): String = "Credentials(loginName=$loginName, appPassword=***)"
}

/** What the add-account flow hands over after Login Flow v2 and the capability check succeeded. */
data class NewAccount(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
    val userId: String,
    val displayName: String?,
    val serverVersion: String?,
    val appVersion: String?,
)

/**
 * Account management. Metadata lives in Room (`account` table), the app password in an encrypted
 * file inside `Context.noBackupFilesDir` (never backed up): AES-256-GCM with a non-exportable key in
 * the Android Keystore (alias `ebookreader_credentials`), random 12 byte IV per value, account id
 * bound as AES-GCM associated data, stored as `iv || ciphertext || tag` Base64. Keys that are
 * invalidated (restore on another device, lock screen removal) make [credentials] return null and
 * the account is shown as "sign in again".
 *
 * Owner: W-DATA (`data/account/AccountStoreImpl.kt` + `KeystoreCredentialCipher.kt`).
 */
interface AccountStore {
    /** All accounts, ordered by creation time. */
    val accounts: Flow<List<Account>>

    suspend fun list(): List<Account>

    suspend fun get(accountId: String): Account?

    /** Persists metadata and encrypted password; returns the new account (random UUID id). Rejects duplicates (same server URL + user id) with [AccountAlreadyExistsException]. */
    suspend fun add(account: NewAccount): Account

    /** Replaces the app password after a re-login (account id, data and downloads are kept). */
    suspend fun updateCredentials(accountId: String, credentials: Credentials)

    /** Decrypted credentials, or null if missing or the keystore key was invalidated. */
    suspend fun credentials(accountId: String): Credentials?

    /**
     * Removes the account locally: credentials, database rows (cascade), downloaded files. Does not
     * contact the server; revoking the app password (`EbookApi.revokeAppPassword`) is done by the caller first.
     */
    suspend fun remove(accountId: String)
}

/** Account as seen by the app (no secrets). */
data class Account(
    val id: String,
    val serverUrl: String,
    val loginName: String,
    val userId: String,
    val displayName: String?,
    val serverVersion: String?,
    val appVersion: String?,
    val lastSyncAt: Long?,
)

class AccountAlreadyExistsException(val existing: Account) : Exception("Account already exists")

/** Android Keystore AES-GCM wrapper. Separate interface so the store is unit-testable with a fake cipher. */
interface CredentialCipher {
    /** @param aad associated data (the account id), authenticated but not encrypted. */
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException if the key is gone or the data was tampered with. */
    fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray
}
