package io.hibira.ktor

import com.fasterxml.jackson.databind.ObjectMapper
import io.hibira.Approval
import io.hibira.ApprovalRequest
import io.hibira.Hibira
import io.hibira.HibiraClient
import io.hibira.HibiraOptions
import io.hibira.LiveChat
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Settings of [HibiraPlugin]. Each falls back to its environment variable. */
public class HibiraConfig {
    /** App ID. Default: `HIBIRA_APP_ID`. */
    public var appId: String? = env("HIBIRA_APP_ID")

    /** App key. Default: `HIBIRA_KEY`. */
    public var key: String? = env("HIBIRA_KEY")

    /** App secret. Default: `HIBIRA_SECRET`. Never send it to a browser. */
    public var secret: String? = env("HIBIRA_SECRET")

    /** API host, as shown on the app page. Default: `HIBIRA_HOST`, then `ws.hibira.io`. */
    public var host: String? = env("HIBIRA_HOST")

    /** API port. Default: `HIBIRA_PORT`, then the scheme's port. */
    public var port: Int? = env("HIBIRA_PORT")?.toIntOrNull()

    /** Use HTTPS. Default: `HIBIRA_USE_TLS` (unless "false"), then true. */
    public var useTls: Boolean = env("HIBIRA_USE_TLS") != "false"

    /** Live Chat › Settings › Widget › Verified visitors secret. Default: `HIBIRA_LIVECHAT_IDENTITY_SECRET`. */
    public var liveChatIdentitySecret: String? = env("HIBIRA_LIVECHAT_IDENTITY_SECRET")

    /** Live Chat workspace ID for [HibiraKtor.liveChatScriptTag]. Default: `HIBIRA_LIVECHAT_WORKSPACE`. */
    public var liveChatWorkspace: String? = env("HIBIRA_LIVECHAT_WORKSPACE")

    /** Use your own client (for tests, or a shared one). */
    public var client: Hibira? = null

    internal fun options(): HibiraOptions {
        val b = HibiraOptions.builder().appId(appId).key(key).secret(secret).useTls(useTls).port(port)
        host?.let { b.host(it) }
        return b.build()
    }
}

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

internal val HibiraKey: AttributeKey<HibiraKtor> = AttributeKey("Hibira")

/**
 * Installs Hibira:
 *
 * ```kotlin
 * install(HibiraPlugin)          // HIBIRA_APP_ID, HIBIRA_KEY, HIBIRA_SECRET
 * routing { hibiraChannelAuth { channel -> allow() } }
 * ```
 *
 * Then `application.hibira` (or `call.hibira`) publishes events, uses the Chat
 * API and Approvals, and signs Live Chat visitors.
 */
public val HibiraPlugin: ApplicationPlugin<HibiraConfig> = createApplicationPlugin("Hibira", ::HibiraConfig) {
    val client = pluginConfig.client ?: HibiraClient.create(pluginConfig.options().validate())
    application.attributes.put(
        HibiraKey,
        HibiraKtor(client, pluginConfig.liveChatIdentitySecret, pluginConfig.liveChatWorkspace),
    )
}

/** The installed Hibira client. */
public val Application.hibira: HibiraKtor
    get() = attributes.getOrNull(HibiraKey) ?: error("Hibira: install(HibiraPlugin) first")

/** The installed Hibira client. */
public val ApplicationCall.hibira: HibiraKtor
    get() = application.hibira

/** Coroutine-friendly access to Hibira from Ktor. The Java client is in [client]. */
public class HibiraKtor internal constructor(
    /** The underlying io.hibira:hibira-java client. */
    public val client: Hibira,
    private val liveChatIdentitySecret: String?,
    private val liveChatWorkspace: String?,
) {
    internal val json: ObjectMapper = ObjectMapper()

    /** The Chat API. */
    public val chat: HibiraChat = HibiraChat(client)

    /** Publishes an event to channels. `data` is serialized to JSON. */
    public suspend fun trigger(channels: List<String>, event: String, data: Any?, socketId: String? = null): Unit =
        withContext(Dispatchers.IO) { client.trigger(channels, event, data, socketId) }

    /** Publishes an event to one channel. */
    public suspend fun trigger(channel: String, event: String, data: Any?): Unit = trigger(listOf(channel), event, data)

    /** Creates an approval; share its `url` with whoever decides. */
    public suspend fun requestApproval(request: ApprovalRequest): Approval =
        withContext(Dispatchers.IO) { client.requestApproval(request) }

    /** Creates an approval from a title and builder settings. */
    public suspend fun requestApproval(title: String, build: ApprovalRequest.Builder.() -> Unit = {}): Approval =
        requestApproval(ApprovalRequest.builder(title).apply(build).build())

    /** An approval's status; `wait` (up to 25 seconds) holds the request until it is answered. */
    public suspend fun approval(approvalId: String, wait: Duration? = null): Approval =
        withContext(Dispatchers.IO) { client.getApproval(approvalId, wait) }

    /** Suspends until the approval is no longer pending, or `timeout` passes. */
    public suspend fun awaitApproval(approvalId: String, timeout: Duration = Duration.ofHours(1)): Approval =
        withContext(Dispatchers.IO) { client.awaitApproval(approvalId, timeout) }

    /** Checks a `callback_url` delivery, signed with the app secret. See [verifyApprovalCallback]. */
    public fun verifyApprovalCallback(signatureHeader: String?, body: ByteArray): ApprovalCallback? =
        verifyApprovalCallback(client.options().secret(), signatureHeader, body)

    /** Verified-visitor fields for `HibiraLiveChat.identify()`, or null without an identity secret or a user. */
    public fun liveChatIdentity(userId: String?, name: String? = null, email: String? = null): Map<String, String>? {
        val secret = liveChatIdentitySecret?.takeIf { it.isNotEmpty() } ?: return null
        if (userId.isNullOrEmpty()) return null
        return LiveChat.identity(secret, userId, name, email)
    }

    /** The Live Chat widget's script tag, or "" without a workspace. */
    public fun liveChatScriptTag(
        userId: String? = null,
        name: String? = null,
        email: String? = null,
        color: String? = null,
        lang: String? = null,
        launcher: Boolean = true,
        nonce: String? = null,
        workspace: String? = liveChatWorkspace,
    ): String {
        if (workspace.isNullOrEmpty()) return ""
        val attrs = mutableListOf("src" to LiveChat.SCRIPT_URL, "data-workspace" to workspace)
        color?.let { attrs += "data-color" to it }
        lang?.let { attrs += "data-lang" to it }
        if (!launcher) attrs += "data-launcher" to "none"
        liveChatIdentity(userId, name, email)?.let { who ->
            attrs += "data-user-id" to who.getValue("id")
            attrs += "data-user-hash" to who.getValue("hash")
            who["name"]?.let { attrs += "data-user-name" to it }
            who["email"]?.let { attrs += "data-user-email" to it }
        }
        nonce?.let { attrs += "nonce" to it }
        return "<script async " + attrs.joinToString(" ") { (k, v) -> "$k=\"${escapeAttr(v)}\"" } + "></script>"
    }
}

private fun escapeAttr(v: String): String =
    v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
