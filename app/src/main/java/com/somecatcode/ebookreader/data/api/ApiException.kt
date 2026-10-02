package com.somecatcode.ebookreader.data.api

/**
 * Error model of the API client. UI maps these to messages; the sync engine decides about retries:
 * [Network] and [Server] and [RateLimited] are retryable, [Unauthorized] marks the account as
 * "needs re-login", the rest are permanent for that request.
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No connection, DNS, TLS or timeout problem. */
    class Network(cause: Throwable) : ApiException(cause.message ?: "Network error", cause)

    /** HTTP 401: app password revoked or wrong. */
    class Unauthorized(message: String = "Unauthorized") : ApiException(message)

    /** HTTP 403: no access, e.g. view-only share or user lacks permission. */
    class Forbidden(message: String = "Forbidden") : ApiException(message)

    /** HTTP 404 (book deleted, endpoint missing). */
    class NotFound(message: String = "Not found") : ApiException(message)

    /** HTTP 400/422: the server rejected the input. */
    class BadRequest(message: String) : ApiException(message)

    /** HTTP 429. [retryAfterSeconds] from `Retry-After` when present. */
    class RateLimited(val retryAfterSeconds: Long?) : ApiException("Rate limited")

    /** HTTP 5xx, maintenance mode (503) or an unparsable response. */
    class Server(val statusCode: Int, message: String) : ApiException(message)
}
