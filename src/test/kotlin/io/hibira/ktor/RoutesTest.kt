package io.hibira.ktor

import com.fasterxml.jackson.databind.ObjectMapper
import io.hibira.HibiraClient
import io.hibira.HibiraOptions
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WR_SECRET = "wr-secret-0123456789"
private const val PASS = "eyJ2IjoxLCJhcHAiOiJ3ci1hcHAiLCJyb29tIjoicHJlc2FsZSIsIm4iOjQyLCJ0aWQiOiJkNzlmZTc4OWEzOGI4NjMxYjUiLCJpYXQiOjE4MDAwMDAwMDAsImV4cCI6MTgwMDAwMTgwMH0.q7Uc_O6XKwNA_LVLQITlgv_YtDi9dIwiRzUUUSsGa08"

class RoutesTest {
    private val json = ObjectMapper()

    private fun ApplicationTestBuilder.setup(secret: String = "7ad3773142a6692b25b8", appId: String = "1") {
        application {
            install(HibiraPlugin) {
                client = HibiraClient.create(HibiraOptions.builder().appId(appId).key("278d425bdf160c739803").secret(secret).build())
            }
            routing {
                hibiraChannelAuth { channel ->
                    when {
                        channel.startsWith("presence-") -> presence("10", mapOf("name" to "Mr. Channels"))
                        channel == "private-foobar" -> allow()
                        else -> deny()
                    }
                }
                hibiraUserAuth { SignedInUser("7") }
                hibiraWebhooks { hook -> response.headers.append("X-Events", hook.events().size.toString()) }
                route("/drop") {
                    install(HibiraWaitingRoom) {
                        room = "presale"
                        clock = { Instant.ofEpochSecond(1800000100) }
                    }
                    get { call.respondText("number ${call.waitingRoomPass?.number()}") }
                    post { call.respondText("posted") }
                }
            }
        }
    }

    @Test
    fun channelAuthMatchesThePusherReference() = testApplication {
        setup()
        val private = client.submitForm("/pusher/auth", parameters { append("socket_id", "1234.1234"); append("channel_name", "private-foobar") })
        assertEquals(
            "278d425bdf160c739803:58df8b0c36d6982b82c3ecf6b4662e34fe8c25bba48f5369f135bf843651c3a4",
            json.readTree(private.bodyAsText()).path("auth").asText(),
        )
        val presence = client.submitForm("/pusher/auth", parameters { append("socket_id", "1234.1234"); append("channel_name", "presence-foobar") })
        val body = json.readTree(presence.bodyAsText())
        assertEquals("10", json.readTree(body.path("channel_data").asText()).path("user_id").asText())
        val denied = client.submitForm("/pusher/auth", parameters { append("socket_id", "1234.1234"); append("channel_name", "private-other") })
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val user = client.submitForm("/pusher/user-auth", parameters { append("socket_id", "1234.1234") })
        assertTrue(json.readTree(user.bodyAsText()).path("auth").asText().startsWith("278d425bdf160c739803:"))
    }

    @Test
    fun webhooksCheckTheSignature() = testApplication {
        setup()
        val body = """{"time_ms":1,"events":[{"name":"channel_occupied","channel":"orders"}]}"""
        val ok = client.post("/hibira/webhooks") {
            header("X-Pusher-Key", "278d425bdf160c739803")
            header("X-Pusher-Signature", hmacHex("7ad3773142a6692b25b8", body.toByteArray()))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK to "1", ok.status to ok.headers["X-Events"])
        val bad = client.post("/hibira/webhooks") {
            header("X-Pusher-Key", "278d425bdf160c739803")
            header("X-Pusher-Signature", "00")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
    }

    @Test
    fun waitingRoom() = testApplication {
        setup(secret = WR_SECRET, appId = "wr-app")
        val noRedirects = createClient { followRedirects = false }
        val first = noRedirects.get("/drop") { header("Host", "shop.test") }
        assertEquals(HttpStatusCode.Found, first.status)
        assertEquals("https://ws.hibira.io/wait/278d425bdf160c739803/presale?return=http%3A%2F%2Fshop.test%2Fdrop", first.headers["Location"])
        assertEquals("no-store", first.headers["Cache-Control"])

        assertEquals(HttpStatusCode.Forbidden, noRedirects.post("/drop") { header("Host", "shop.test") }.status)

        val back = noRedirects.get("/drop?a=1&hibira_pass=$PASS") { header("Host", "shop.test"); header("X-Forwarded-Proto", "https") }
        assertEquals(HttpStatusCode.Found, back.status)
        assertEquals("https://shop.test/drop?a=1", back.headers["Location"])
        assertTrue(back.headers["Set-Cookie"]!!.startsWith("hibira_wr_presale=$PASS; Path=/; Max-Age=1700; HttpOnly"))

        val inside = noRedirects.get("/drop") { header("Host", "shop.test"); header("Cookie", "hibira_wr_presale=$PASS") }
        assertEquals("number 42", inside.bodyAsText())
    }
}
