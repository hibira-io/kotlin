# hibira-ktor

[Hibira](https://hibira.io) for [Ktor](https://ktor.io) 3 servers, in Kotlin.
It builds on [`io.hibira:hibira-java`](https://github.com/hibira-io/spring-boot)
and adds coroutines and Ktor plugins:

- **Realtime** (Pusher protocol): publish events, channel and user auth routes for pusher-js and Laravel Echo, signed webhooks.
- **Waiting rooms**: a route plugin that sends visitors without a pass to the hosted queue.
- **Chat API**: conversations with stored history, read receipts and offline push.
- **Approvals**: ask a person for a decision and suspend until they answer.
- **Live Chat**: verified visitors for the Hibira Live Chat widget.

## Using an AI coding assistant?

Add the [Hibira MCP server](https://hibira.io/guides) to Claude Code, Cursor
or Codex and ask it to "add Hibira to this Ktor app": `setup_project` creates
the app and env values, and `get_integration_guide` hands the assistant these
steps.

## Install

```kotlin
dependencies {
    implementation("io.hibira:hibira-ktor:0.1.0")
}
```

Java 17 or newer. Set the app's credentials from the Hibira console:

```sh
HIBIRA_APP_ID=123
HIBIRA_KEY=...
HIBIRA_SECRET=...            # server only
HIBIRA_HOST=ws.hibira.io     # as shown on the app page
```

```kotlin
fun Application.module() {
    install(HibiraPlugin)   // or install(HibiraPlugin) { appId = ...; key = ...; secret = ... }
    routing {
        hibiraChannelAuth { channel ->
            val user = principal<UserIdPrincipal>() ?: return@hibiraChannelAuth deny()
            if (channel.startsWith("presence-")) presence(user.name, mapOf("name" to user.name)) else allow()
        }
        hibiraUserAuth { principal<UserIdPrincipal>()?.let { SignedInUser(it.name) } }
        hibiraWebhooks { hook -> hook.events().forEach { log.info(it.name()) } }
    }
}
```

The routes default to `/pusher/auth`, `/pusher/user-auth` and
`/hibira/webhooks`. Publish from anywhere with
`application.hibira.trigger("orders", "order.shipped", mapOf("id" to 1042))`.

## Waiting rooms

Create a room in the Hibira console (or with the MCP `set_waiting_room`
tool), then install the plugin on the routes it protects:

```kotlin
route("/drop") {
    install(HibiraWaitingRoom) { room = "presale" }
    get { call.respondText("You are number ${call.waitingRoomPass!!.number()}") }
}
```

A visitor without a valid pass is redirected to the hosted waiting page (a
request other than GET or HEAD gets a 403). The pass they bring back is
stored in the HttpOnly cookie `hibira_wr_<room>`. Record
`waitingRoomPass.ticketId()` to allow one checkout per queue number.

## Chat API

```kotlin
val chat = application.hibira.chat
chat.putConversation("order-1042", name = "Order #1042", members = listOf("buyer-7", "seller-3"))
chat.sendMessage("order-1042", "seller-3", "Shipped today!", push = "offline")
val page = chat.listMessages("order-1042", after = 0, limit = 50)
chat.markRead("order-1042", "buyer-7", 12)
```

`getConversation`, `deleteConversation`, `addMembers`, `removeMember`,
`systemMessage`, `userConversations` and `request` (any other route) cover
the rest. Use conversation and user IDs without spaces or `/` until
hibira-java 0.3.1 ships its signing fix. See the
[reference](https://hibira.io/chat-api/reference).

## Approvals

```kotlin
val hibira = application.hibira
val ask = hibira.requestApproval("Refund order #1042 for $89?") { options("Refund", "Decline") }
// share ask.url() with whoever decides, then
val answer = hibira.awaitApproval(ask.approvalId(), Duration.ofMinutes(10))
if (answer.isApproved()) refund()
```

`approval(id, wait)` reads one status. Check a `callback_url` delivery with
`hibira.verifyApprovalCallback(call.request.headers["Hibira-Signature"], body)`.

## Live Chat

```kotlin
install(HibiraPlugin) { liveChatWorkspace = "ws_..."; liveChatIdentitySecret = System.getenv("HIBIRA_LIVECHAT_IDENTITY_SECRET") }
// in a page:
val tag = call.hibira.liveChatScriptTag(userId = user.id, name = user.name, email = user.email)
```

For a single-page app, return `call.hibira.liveChatIdentity(user.id, user.name, user.email)`
as JSON and call `HibiraLiveChat.identify(it)` in the browser.

## Development

```sh
./gradlew build
```

## 中文摘要

`hibira-ktor` 讓 Ktor 3 伺服器使用 [Hibira](https://hibira.io)，以 `io.hibira:hibira-java` 為基礎，提供協程與 Ktor plugin：

- **即時（Pusher 協定）**：`install(HibiraPlugin)` 後用 `hibiraChannelAuth`、`hibiraUserAuth`、`hibiraWebhooks` 建立頻道授權、使用者登入與 webhook 路由；`application.hibira.trigger()` 發送事件。
- **等候室**：在路由上 `install(HibiraWaitingRoom) { room = "presale" }`，沒有 pass 的訪客會被導到託管排隊頁，帶回的 pass 存成 HttpOnly cookie。
- **Chat API**：`hibira.chat` 建立對話、發訊息、讀取歷史、標記已讀。
- **Approvals**：`requestApproval()` 發出請求，`awaitApproval()` 以 suspend 等待結果，`verifyApprovalCallback()` 驗證 callback。
- **Live Chat**：`liveChatScriptTag()`、`liveChatIdentity()` 讓登入使用者以驗證過的身分聊天。
- 安裝：`implementation("io.hibira:hibira-ktor:0.1.0")`，需要 Java 17 以上，設定 `HIBIRA_APP_ID` / `HIBIRA_KEY` / `HIBIRA_SECRET`。
