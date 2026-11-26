package io.hibira.ktor

import io.hibira.WaitingRoom
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.time.Instant

/** Settings of [HibiraWaitingRoom]. */
public class WaitingRoomConfig {
    /** The room's ID, as in the Hibira console. Required. */
    public var room: String = ""

    /** The hosted waiting page's language: en, zh-TW, zh-CN, ja, ko, th, vi, it or fr. */
    public var lang: String? = null

    /** Defaults to the app's secret; give the old one too during a rotation. */
    public var secrets: List<String>? = null

    /** Defaults to `hibira_wr_<room>`. */
    public var cookieName: String? = null

    /** The hosted page's origin; defaults to https://ws.hibira.io. */
    public var host: String? = null

    /** For tests. */
    public var clock: () -> Instant = Instant::now
}

private val PassKey = AttributeKey<WaitingRoom.Pass>("HibiraWaitingRoomPass")

/** The pass [HibiraWaitingRoom] let through, or null outside a protected route. */
public val ApplicationCall.waitingRoomPass: WaitingRoom.Pass?
    get() = attributes.getOrNull(PassKey)

/**
 * Puts a Hibira waiting room in front of a route. Visitors without a valid
 * pass go to the hosted waiting page (a request other than GET or HEAD gets a
 * 403); the pass they bring back is stored in an HttpOnly cookie.
 *
 * ```kotlin
 * route("/drop") {
 *     install(HibiraWaitingRoom) { room = "presale" }
 *     get { call.respondText("You are number ${call.waitingRoomPass!!.number()}") }
 * }
 * ```
 */
public val HibiraWaitingRoom: RouteScopedPlugin<WaitingRoomConfig> =
    createRouteScopedPlugin("HibiraWaitingRoom", ::WaitingRoomConfig) {
        val cfg = pluginConfig
        require(cfg.room.isNotEmpty()) { "HibiraWaitingRoom: set room" }
        onCall { call ->
            val opts = call.hibira.client.options()
            val options = WaitingRoom.Options(
                opts.appId(), opts.key(), cfg.secrets ?: listOf(opts.secret()), cfg.room, cfg.host, cfg.lang, cfg.cookieName,
            )
            val d = WaitingRoom.decide(requestUrl(call), call.request.local.method.value, call.request.cookies[options.cookieName()], options, cfg.clock())
            when (d.action()) {
                WaitingRoom.Action.ALLOW -> call.attributes.put(PassKey, d.pass())
                WaitingRoom.Action.DENY -> {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondText(WaitingRoom.DENY_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                else -> {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    d.setCookie()?.let { call.response.headers.append(HttpHeaders.SetCookie, it) }
                    call.response.headers.append(HttpHeaders.Location, d.location())
                    call.respondText("", status = HttpStatusCode.Found)
                }
            }
        }
    }

private fun requestUrl(call: ApplicationCall): String {
    val origin = call.request.origin
    val forwarded = call.request.headers["X-Forwarded-Proto"]?.equals("https", ignoreCase = true) == true
    val scheme = if (forwarded) "https" else origin.scheme
    val host = call.request.headers[HttpHeaders.Host] ?: "${origin.serverHost}:${origin.serverPort}"
    return "$scheme://$host${call.request.uri}"
}
