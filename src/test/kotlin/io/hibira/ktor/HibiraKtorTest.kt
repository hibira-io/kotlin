package io.hibira.ktor

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HibiraKtorTest {
    private val json = ObjectMapper()

    @Test
    fun chatRoutesAreSigned() = FakeApi().use { api ->
        runBlocking {
            val chat = HibiraKtor(api.client, null, null).chat
            chat.putConversation("order 1", name = "Order 1", members = listOf("u1", "u2"))
            chat.getConversation("order-1")
            chat.deleteConversation("order-1")
            chat.addMembers("c1", listOf("u3"))
            chat.removeMember("c1", "u/3")
            chat.sendMessage("c1", "u1", "Hi", push = "offline")
            chat.listMessages("c1", after = 5, limit = 20)
            chat.markRead("c1", "u1", 7)
            chat.systemMessage("c1", "Alice joined")
            chat.userConversations("u1", limit = 10, before = "c9")
            chat.request("POST", "/conversations/c1/typing")
        }
        val s = api.seen
        assertEquals("PUT" to "/apps/1/chat/conversations/order%201", s[0].method to s[0].path)
        assertEquals(json.readTree("""{"name":"Order 1","members":["u1","u2"]}"""), json.readTree(s[0].body))
        assertEquals("GET" to "", s[1].method to s[1].body)
        assertEquals("DELETE", s[2].method)
        assertEquals(json.readTree("""{"user_ids":["u3"]}"""), json.readTree(s[3].body))
        assertEquals("/apps/1/chat/conversations/c1/members/u%2F3", s[4].path)
        assertEquals(json.readTree("""{"user_id":"u1","text":"Hi","push":"offline"}"""), json.readTree(s[5].body))
        assertEquals(mapOf("after" to "5", "limit" to "20"), userQuery(s[6]))
        assertEquals(json.readTree("""{"user_id":"u1","seq":7}"""), json.readTree(s[7].body))
        assertEquals(json.readTree("""{"text":"Alice joined"}"""), json.readTree(s[8].body))
        assertEquals(mapOf("limit" to "10", "before" to "c9"), userQuery(s[9]))
        assertEquals("/apps/1/chat/conversations/c1/typing" to "{}", s[10].path to s[10].body)
        s.forEach(::assertSigned)
    }

    @Test
    fun approvalsAndTrigger() = FakeApi(
        listOf(
            201 to """{"approval_id":"apr_1","title":"Refund?","status":"pending","url":"https://approvals.hibira.io/a/apr_1"}""",
            200 to """{"approval_id":"apr_1","title":"Refund?","status":"approved","url":"u"}""",
        ),
    ).use { api ->
        val hibira = HibiraKtor(api.client, null, null)
        runBlocking {
            val a = hibira.requestApproval("Refund?") { options("Refund", "Decline") }
            assertEquals("https://approvals.hibira.io/a/apr_1", a.url())
            assertTrue(hibira.approval("apr_1", java.time.Duration.ofSeconds(90)).isApproved())
            hibira.trigger("orders", "order.shipped", mapOf("id" to 1042))
        }
        assertEquals(json.readTree("""{"title":"Refund?","options":["Refund","Decline"]}"""), json.readTree(api.seen[0].body))
        assertEquals(mapOf("wait" to "25"), userQuery(api.seen[1]))
        assertEquals("/apps/1/events", api.seen[2].path)
        api.seen.forEach(::assertSigned)
    }

    @Test
    fun approvalCallbacks() {
        val body = """{"id":"evt_1","type":"approval.decided","data":{"approval":{"approval_id":"apr_1","status":"approved"}}}""".toByteArray()
        val t = 1800000000L
        val sig = hmacHex("whsec", "$t.".toByteArray() + body)
        val cb = verifyApprovalCallback("whsec", "t=$t,v1=$sig", body, t + 1)
        assertEquals("approval.decided", cb?.type)
        assertEquals("approved", cb?.approval?.path("status")?.asText())
        assertTrue(verifyApprovalCallback("whsec", "t=$t,v1=00,v1=$sig", body, t) != null)
        assertNull(verifyApprovalCallback("other", "t=$t,v1=$sig", body, t))
        assertNull(verifyApprovalCallback("whsec", "t=$t,v1=$sig", "{}".toByteArray(), t))
        assertNull(verifyApprovalCallback("whsec", "t=$t,v1=$sig", body, t + 301))
        assertNull(verifyApprovalCallback("whsec", null, body, t))
    }

    @Test
    fun liveChat() {
        val hibira = HibiraKtor(FakeApi().use { it.client }, "idsecret", "ws_1")
        val hash = hmacHex("idsecret", "42".toByteArray())
        assertEquals(mapOf("id" to "42", "hash" to hash, "name" to "Ann"), hibira.liveChatIdentity("42", "Ann", ""))
        assertNull(hibira.liveChatIdentity(null))
        assertEquals(
            """<script async src="https://chat.hibira.io/w/v1.js" data-workspace="ws_1" data-launcher="none" data-user-id="42" data-user-hash="$hash" data-user-name="A &quot;B&quot; &lt;c&gt;" nonce="n1"></script>""",
            hibira.liveChatScriptTag("42", "A \"B\" <c>", launcher = false, nonce = "n1"),
        )
        assertEquals("", HibiraKtor(hibira.client, null, null).liveChatScriptTag())
    }
}
