package com.somecatcode.ebookreader.data.api

import com.somecatcode.ebookreader.data.account.AccountStore
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates [EbookApi] instances; one cached client per account id (rebuilt when the credentials
 * or the server URL change, e.g. after a re-login). HTTPS only; [allowInsecure] exists solely for
 * unit tests against MockWebServer and is never set in the app.
 */
class ApiClientFactoryImpl(
    private val accountStore: AccountStore,
    private val httpClient: OkHttpClient,
    private val allowInsecure: Boolean = false,
) : ApiClientFactory {

    private data class Entry(val serverUrl: String, val loginName: String, val appPassword: String, val api: EbookApi)

    private val cache = ConcurrentHashMap<String, Entry>()

    override suspend fun forAccount(accountId: String): EbookApi {
        val account = accountStore.get(accountId) ?: throw ApiException.Unauthorized("Unknown account")
        val creds = accountStore.credentials(accountId) ?: throw ApiException.Unauthorized("No credentials")
        cache[accountId]?.let {
            if (it.serverUrl == account.serverUrl && it.loginName == creds.loginName && it.appPassword == creds.appPassword) {
                return it.api
            }
        }
        val api = forCredentials(account.serverUrl, creds.loginName, creds.appPassword)
        cache[accountId] = Entry(account.serverUrl, creds.loginName, creds.appPassword, api)
        return api
    }

    override fun forCredentials(serverUrl: String, loginName: String, appPassword: String): EbookApi {
        val url = serverUrl.trim().toHttpUrlOrNull()
        if (url == null || (!url.isHttps && !allowInsecure)) {
            throw ApiException.BadRequest("Only HTTPS servers are supported")
        }
        return EbookApiImpl(serverUrl, loginName, appPassword, httpClient)
    }

    /** Drops the cached client (account removed). */
    fun forget(accountId: String) {
        cache.remove(accountId)
    }
}
