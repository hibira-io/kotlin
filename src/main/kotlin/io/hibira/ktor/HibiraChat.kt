package io.hibira.ktor

import com.fasterxml.jackson.databind.JsonNode
import io.hibira.Hibira
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The Chat API (https://hibira.io/chat-api): conversations with stored
 * history, read receipts and offline push. Clients receive `chat.*` events on
 * `#server-to-user-<user_id>` after pusher-js `signin()`. Errors throw
 * `io.hibira.HibiraException` with the HTTP status.
 */
public class HibiraChat internal constructor(private val client: Hibira) {
    /** Any Chat API call, signed. `path` is relative to /apps/APP_ID/chat, such as `/conversations/c1/typing`. */
    public suspend fun request(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: Any? = null): JsonNode {
        val verb = method.uppercase()
        val p = if (path.startsWith("/")) path else "/$path"
        val q = query.filterValues { it != null }.mapValues { it.value.toString() }
        val payload = body ?: if (verb == "POST" || verb == "PUT") emptyMap<String, Any>() else null
        return withContext(Dispatchers.IO) { client.chat(verb, p, q, payload) }
    }

    /** Creates or replaces a conversation. `members` null keeps the current members. */
    public suspend fun putConversation(
        id: String,
        name: String? = null,
        members: List<String>? = null,
        metadata: Map<String, Any?>? = null,
        roles: Map<String, String>? = null,
        watchers: List<String>? = null,
    ): JsonNode = request(
        "PUT",
        "/conversations/${seg(id)}",
        body = clean("name" to name, "members" to members, "metadata" to metadata, "roles" to roles, "watchers" to watchers),
    )

    public suspend fun getConversation(id: String): JsonNode = request("GET", "/conversations/${seg(id)}")

    /** Deletes a conversation with its members, messages and files. */
    public suspend fun deleteConversation(id: String): JsonNode = request("DELETE", "/conversations/${seg(id)}")

    public suspend fun addMembers(id: String, userIds: List<String>): JsonNode =
        request("POST", "/conversations/${seg(id)}/members", body = mapOf("user_ids" to userIds))

    public suspend fun removeMember(id: String, userId: String): JsonNode =
        request("DELETE", "/conversations/${seg(id)}/members/${seg(userId)}")

    /** Posts a message as a member. `push` is "offline" (default), "always" or "never". */
    public suspend fun sendMessage(
        id: String,
        userId: String,
        text: String,
        push: String? = null,
        pushTitle: String? = null,
        pushUrl: String? = null,
        metadata: Map<String, Any?>? = null,
        visibleTo: List<String>? = null,
        idempotencyKey: String? = null,
    ): JsonNode = request(
        "POST",
        "/conversations/${seg(id)}/messages",
        body = clean(
            "user_id" to userId, "text" to text, "push" to push, "push_title" to pushTitle, "push_url" to pushUrl,
            "metadata" to metadata, "visible_to" to visibleTo, "idempotency_key" to idempotencyKey,
        ),
    )

    /** Messages oldest first: after a seq (with limit), or the last n. */
    public suspend fun listMessages(id: String, after: Long? = null, limit: Int? = null, last: Int? = null): JsonNode =
        request("GET", "/conversations/${seg(id)}/messages", mapOf("after" to after, "limit" to limit, "last" to last))

    public suspend fun markRead(id: String, userId: String, seq: Long): JsonNode =
        request("POST", "/conversations/${seg(id)}/read", body = mapOf("user_id" to userId, "seq" to seq))

    /** A message with no sender, such as "Alice joined". */
    public suspend fun systemMessage(id: String, text: String): JsonNode =
        request("POST", "/conversations/${seg(id)}/system", body = mapOf("text" to text))

    /** A user's conversations, latest activity first, each with its unread count. */
    public suspend fun userConversations(userId: String, limit: Int? = null, before: String? = null): JsonNode =
        request("GET", "/users/${seg(userId)}/conversations", mapOf("limit" to limit, "before" to before))
}

private fun clean(vararg pairs: Pair<String, Any?>): Map<String, Any> =
    pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

private fun seg(id: String): String {
    require(id.isNotEmpty()) { "an ID is required" }
    return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")
}
