package top.wkbin.zaomeng.ktor.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** Unified JSON error response used by HTTP routes and plugins. */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, mapOf("error" to message))
}

/** Maps common route-layer exceptions to stable HTTP statuses and error payloads. */
suspend fun ApplicationCall.handleRouteException(error: Exception) {
    when (error) {
        is NoSuchElementException -> respondError(
            HttpStatusCode.NotFound,
            error.message ?: "Not found",
        )
        is IllegalArgumentException -> respondError(
            HttpStatusCode.BadRequest,
            error.message ?: "Invalid request",
        )
        else -> respondError(
            HttpStatusCode.InternalServerError,
            error.message ?: "Internal server error",
        )
    }
}
