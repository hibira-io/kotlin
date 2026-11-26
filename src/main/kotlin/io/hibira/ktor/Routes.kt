package io.hibira.ktor

import io.hibira.PresenceMember
import io.hibira.Webhook
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray

/** What [hibiraChannelAuth] decides for a subscription. */
public sealed interface ChannelAccess {
    public data object Allow : ChannelAccess

    public data object Deny : ChannelAccess

    public data class Presence(val member: PresenceMember) : ChannelAccess
}

/** Lets the user subscribe to a private channel. */
public fun allow(): ChannelAccess = ChannelAccess.Allow

/** Refuses the subscription (403). */
public fun deny(): ChannelAccess = ChannelAccess.Deny

/** Lets the user join a presence channel as this member. */
public fun presence(userId: String, userInfo: Any? = null): ChannelAccess =
    ChannelAccess.Presence(PresenceMember.of(userId, userInfo))

/**
 * The channel authorization endpoint for pusher-js / Laravel Echo
 * (`channelAuthorization.endpoint`). `authorize` gets the channel name and
 * returns [allow], [presence] or [deny].
 */
public fun Route.hibiraChannelAuth(
    path: String = "/pusher/auth",
    authorize: suspend ApplicationCall.(channel: String) -> ChannelAccess,
): Route = post(path) {
    val form = call.receiveText().parseUrlEncodedParameters()
    val socketId = form["socket_id"].orEmpty()
    val channel = form["channel_name"].orEmpty()
    if (socketId.isEmpty() || channel.isEmpty()) {
        call.respondText("""{"error":"socket_id and channel_name are required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        return@post
    }
    val hibira = call.hibira
    val auth = when (val access = call.authorize(channel)) {
        ChannelAccess.Deny -> null
        ChannelAccess.Allow -> if (channel.startsWith("presence-")) null else hibira.client.authorizeChannel(socketId, channel)
        is ChannelAccess.Presence -> hibira.client.authorizePresenceChannel(socketId, channel, access.member)
    }
    if (auth == null) {
        call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
    } else {
        call.respondText(hibira.json.writeValueAsString(auth), ContentType.Application.Json)
    }
}

/** A signed-in user for [hibiraUserAuth]. */
public data class SignedInUser(val id: String, val info: Map<String, Any?> = emptyMap())

/**
 * The user authentication endpoint for pusher-js `signin()`
 * (`userAuthentication.endpoint`). Return null for a guest (403).
 */
public fun Route.hibiraUserAuth(
    path: String = "/pusher/user-auth",
    user: suspend ApplicationCall.() -> SignedInUser?,
): Route = post(path) {
    val form = call.receiveText().parseUrlEncodedParameters()
    val socketId = form["socket_id"].orEmpty()
    val who = call.user()
    if (socketId.isEmpty() || who == null) {
        call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
        return@post
    }
    val hibira = call.hibira
    call.respondText(hibira.json.writeValueAsString(hibira.client.authenticateUser(socketId, who.id, who.info)), ContentType.Application.Json)
}

/**
 * Receives Hibira webhooks (channel occupied/vacated, member added/removed,
 * client events), checking `X-Pusher-Key` and `X-Pusher-Signature`. Answers
 * 401 for a bad signature and 200 after `handle`.
 */
public fun Route.hibiraWebhooks(
    path: String = "/hibira/webhooks",
    handle: suspend ApplicationCall.(Webhook) -> Unit,
): Route = post(path) {
    val body = call.receiveChannel().toByteArray()
    val webhook = call.hibira.client.verifyWebhook(
        call.request.headers["X-Pusher-Key"],
        call.request.headers["X-Pusher-Signature"],
        call.request.headers["X-Pusher-Signature-Previous"],
        body,
    )
    if (webhook == null) {
        call.respondText("""{"error":"invalid signature"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        return@post
    }
    call.handle(webhook)
    call.respondText("{}", ContentType.Application.Json)
}
