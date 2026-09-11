package dev.wyz.clientcore.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.wyz.clientcore.RiverRuntime
import net.minecraft.client.Minecraft
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors

/**
 * River friends, direct messages and moderation, client side.
 *
 * Identity is proven with Mojang's own session handshake rather than asserted, because the
 * backend refuses to take a client's word for who it is: River hands out a one-time
 * serverId, this calls [com.mojang.authlib.minecraft.MinecraftSessionService.joinServer]
 * with it, and the backend then asks Mojang hasJoined to confirm. Only Mojang's answer is
 * believed, so a message can never be forged as another player.
 *
 * Every network call runs on a small daemon pool and results are handed back on the client
 * main thread, so nothing here touches the render or tick loop.
 */
object RiverSocial {

    private const val BASE = "https://updates.riverclient.xyz/social"
    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 10000
    /** How often the friends list refreshes while a social screen is open. */
    const val REFRESH_INTERVAL_MS = 10_000L

    private const val SOCKET_URL = "wss://updates.riverclient.xyz/social/ws"
    private const val SOCKET_PROTOCOL = "river.social.v1"
    /** Long enough to stay out of the way, short enough to beat an idle proxy timeout. */
    private const val SOCKET_PING_MS = 4L * 60L * 1000L
    private const val SOCKET_MAX_BACKOFF_MS = 2L * 60L * 1000L

    data class Friend(val uuid: String, val name: String, val online: Boolean, val server: String)
    data class Request(val from: String, val name: String)
    data class Message(val id: String, val from: String, val fromName: String, val text: String, val at: Long)

    @Volatile var token: String = ""
        private set
    @Volatile var selfUuid: String = ""
        private set
    @Volatile var signingIn: Boolean = false
        private set
    @Volatile var error: String = ""
        private set

    @Volatile var friends: List<Friend> = emptyList()
        private set
    @Volatile var requests: List<Request> = emptyList()
        private set

    val signedIn: Boolean get() = token.isNotEmpty()

    private val pool = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "River-Social").apply { isDaemon = true }
    }

    private var lastRefresh = 0L
    private var autoSignInTried = false

    private data class Certificate(
        val publicKeyBase64: String,
        val privateKeyBase64: String,
        val signature: String,
        val expiresAt: String
    )

    /**
     * The player's Mojang-signed certificate - the same one that signs in-game chat.
     * Fetched from the player's own machine, which Mojang serves happily; it is only
     * Cloudflare's egress that they block.
     *
     * Mojang labels these PEM blocks "RSA PRIVATE/PUBLIC KEY" (PKCS#1) but the bodies are
     * actually PKCS#8 and SPKI, so the headers are stripped and the base64 used directly
     * rather than trusting the label.
     */
    private fun fetchCertificate(accessToken: String): Certificate? {
        val connection = (URL("https://api.minecraftservices.com/player/certificates").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val payload = JsonParser.parseString(text).asJsonObject
            val keyPair = payload.getAsJsonObject("keyPair") ?: return null
            Certificate(
                publicKeyBase64 = stripPem(keyPair.get("publicKey")?.asString ?: return null),
                privateKeyBase64 = stripPem(keyPair.get("privateKey")?.asString ?: return null),
                // V2 covers uuid + expiry + key; the original signature omitted the uuid.
                signature = payload.get("publicKeySignatureV2")?.asString ?: return null,
                expiresAt = payload.get("expiresAt")?.asString ?: return null
            )
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun stripPem(pem: String): String =
        pem.replace(Regex("-----[^-]+-----"), "").replace(Regex("\\s+"), "")

    /** Signs River's one-time challenge, proving we hold the certificate's private key. */
    private fun sign(nonce: String, privateKeyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(privateKeyBase64)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key)
        signer.update(nonce.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    // ------------------------------------------------------------------ session

    /**
     * Signs in if needed. Safe to call repeatedly - it no-ops while a sign-in is already
     * running or once a session exists.
     *
     * Uses the Mojang-signed player certificate rather than a joinServer handshake: River's
     * backend runs on Cloudflare, and Mojang answers every Cloudflare request with 403, so
     * the backend cannot call hasJoined. It can verify the certificate's signature offline,
     * and this proves possession by signing a one-time nonce with the certificate's key.
     */
    fun ensureSession(onDone: (Boolean) -> Unit = {}) {
        if (signedIn) { onDone(true); return }
        if (signingIn) return
        signingIn = true
        error = ""

        val client = Minecraft.getInstance()
        pool.submit {
            val result = runCatching {
                val begin = post("/auth/begin", JsonObject()) ?: error("River is unreachable.")
                val nonce = begin.get("nonce")?.asString ?: error("River did not start a sign-in.")

                val certificate = fetchCertificate(client.user.accessToken)
                    ?: error("Could not get your Minecraft certificate from Mojang.")

                val body = JsonObject().apply {
                    addProperty("name", client.user.name)
                    addProperty("uuid", client.user.profileId.toString())
                    addProperty("nonce", nonce)
                    addProperty("publicKey", certificate.publicKeyBase64)
                    addProperty("publicKeySignature", certificate.signature)
                    addProperty("expiresAt", certificate.expiresAt)
                    addProperty("nonceSignature", sign(nonce, certificate.privateKeyBase64))
                }
                val complete = post("/auth/complete", body) ?: error("River is unreachable.")
                if (complete.get("ok")?.asBoolean != true) {
                    error(complete.get("message")?.asString ?: "River could not verify your account.")
                }
                complete
            }

            client.execute {
                signingIn = false
                result.onSuccess { payload ->
                    token = payload.get("token")?.asString ?: ""
                    selfUuid = payload.get("uuid")?.asString ?: ""
                    onDone(signedIn)
                    if (signedIn) {
                        socketClosed = false
                        openSocket()
                        refresh(force = true)
                    }
                }.onFailure {
                    error = it.message ?: "Could not sign in to River social."
                    onDone(false)
                }
            }
        }
    }

    fun signOut() {
        closeSocket()
        token = ""
        selfUuid = ""
        friends = emptyList()
        requests = emptyList()
        error = ""
    }

    // ------------------------------------------------------------------ live socket

    /*
      Friend requests and direct messages arrive as they are sent rather than being found
      by the next poll.

      The refresh below runs every ten seconds while you are in a world, whether or not
      anything has happened, and a message could sit unseen for that long. This costs one
      connection and says nothing until there is something to say.

      Unlike the launcher sockets, this one is authenticated: Java can set headers on a
      handshake, so the same bearer token every other call uses travels with it and the
      server knows whose socket this is without anything being put in the URL.
    */
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketClosed = false
    private val socketAttempts = AtomicInteger(0)
    private val socketText = StringBuilder()
    @Volatile private var lastPing = 0L

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build()
    }

    fun openSocket() {
        if (!signedIn || socket != null || socketClosed) return
        try {
            httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .subprotocols(SOCKET_PROTOCOL)
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(URI.create(SOCKET_URL), SocketListener())
                .whenComplete { ws, error ->
                    if (error != null || ws == null) {
                        scheduleReconnect()
                    } else {
                        socket = ws
                        socketAttempts.set(0)
                        lastPing = System.currentTimeMillis()
                    }
                }
        } catch (_: Throwable) {
            scheduleReconnect()
        }
    }

    fun closeSocket() {
        socketClosed = true
        val current = socket
        socket = null
        try { current?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") } catch (_: Throwable) {}
    }

    /*
      Backs off so a server that is down is not hammered, and jitters so every client does
      not come back in the same second when it returns.
    */
    private fun scheduleReconnect() {
        if (socketClosed || !signedIn) return
        val attempt = socketAttempts.incrementAndGet()
        val base = minOf(5000L * (1L shl minOf(attempt - 1, 5)), SOCKET_MAX_BACKOFF_MS)
        val wait = base / 2 + (Math.random() * (base / 2)).toLong()
        pool.execute {
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return@execute }
            socket = null
            openSocket()
        }
    }

    private class SocketListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            /*
              A frame can arrive in pieces, so it is only parsed once the runtime says
              this is the last part of it.
            */
            socketText.append(data)
            if (last) {
                val whole = socketText.toString()
                socketText.setLength(0)
                handleSocketText(whole)
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            socket = null
            scheduleReconnect()
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            socket = null
            scheduleReconnect()
            return null
        }
    }

    private fun handleSocketText(raw: String) {
        if (raw == "pong") return
        val payload = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Throwable) {
            return
        }
        when (payload.get("type")?.asString) {
            "dm" -> {
                val message = payload.getAsJsonObject("message") ?: return
                val notice = Message(
                    id = message.get("id")?.asString.orEmpty(),
                    from = message.get("from")?.asString ?: return,
                    fromName = message.get("fromName")?.asString.orEmpty(),
                    text = message.get("text")?.asString.orEmpty(),
                    at = message.get("at")?.asLong ?: System.currentTimeMillis()
                )
                pendingNotices = listOf(notice) + pendingNotices
            }
            // The roster is the source of truth for who your friends are, so rather than
            // patching it from the push, ask for it again now there is a reason to.
            "friend-added" -> refresh(force = true)
        }
    }

    /** Keeps the connection from being dropped by an idle proxy. Driven from the tick. */
    private fun pingSocket() {
        val current = socket ?: return
        val now = System.currentTimeMillis()
        if (now - lastPing < SOCKET_PING_MS) return
        lastPing = now
        try { current.sendText("ping", true) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ friends

    /** Heartbeat + friends refresh. Rate-limited unless [force]. */
    fun refresh(force: Boolean = false, shareServer: Boolean = false) {
        if (!signedIn) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < REFRESH_INTERVAL_MS) return
        lastRefresh = now

        val client = Minecraft.getInstance()
        val server = if (shareServer) client.currentServer?.ip.orEmpty() else ""
        val body = JsonObject().apply {
            addProperty("shareServer", shareServer)
            addProperty("server", server)
        }
        call("/presence", body) { payload ->
            if (payload != null) applyRoster(payload)
        }
    }

    /** Newest first: unseen DMs handed over by the last heartbeat. */
    @Volatile var pendingNotices: List<Message> = emptyList()
        private set

    fun clearNotices() { pendingNotices = emptyList() }

    /**
     * Driven from the client tick so presence keeps ticking while you actually play.
     *
     * Previously the heartbeat only ran while the friends screen was open, which meant a
     * friend deep in a server - the exact case you want to see - showed as offline to
     * everyone. Also signs in once per session so presence works without opening the menu.
     */
    fun tick(client: Minecraft) {
        if (client.level == null) return
        if (!signedIn) {
            if (!autoSignInTried && !signingIn) {
                autoSignInTried = true
                ensureSession()
            }
            return
        }
        /*
          The refresh still runs. The socket delivers messages and friend requests, but
          who is online is not pushed, and this is also the heartbeat that keeps you in
          the presence roster while you play - so it stays, and the socket saves the
          waiting rather than the request.
        */
        if (socket == null) openSocket()
        pingSocket()
        // refresh() rate-limits itself to REFRESH_INTERVAL_MS.
        refresh(shareServer = RiverRuntime.config.friendsShareServer)
    }

    private fun applyRoster(payload: JsonObject) {
        friends = payload.getAsJsonArray("friends")?.mapNotNull { element ->
            val obj = element.asJsonObject
            Friend(
                uuid = obj.get("uuid")?.asString ?: return@mapNotNull null,
                name = obj.get("name")?.asString ?: return@mapNotNull null,
                online = obj.get("online")?.asBoolean == true,
                server = obj.get("server")?.asString.orEmpty()
            )
        } ?: emptyList()

        requests = payload.getAsJsonArray("requests")?.mapNotNull { element ->
            val obj = element.asJsonObject
            Request(
                from = obj.get("from")?.asString ?: return@mapNotNull null,
                name = obj.get("name")?.asString ?: return@mapNotNull null
            )
        } ?: emptyList()

        // Drained server-side, so anything here has never been shown before.
        val notices = payload.getAsJsonArray("inbox")?.mapNotNull { element ->
            val obj = element.asJsonObject
            Message(
                id = "",
                from = obj.get("from")?.asString ?: return@mapNotNull null,
                fromName = obj.get("fromName")?.asString.orEmpty(),
                text = obj.get("text")?.asString.orEmpty(),
                at = obj.get("at")?.asLong ?: 0L
            )
        } ?: emptyList()
        if (notices.isNotEmpty()) pendingNotices = notices + pendingNotices
    }

    /**
     * Resolves the name to a UUID against Mojang here, on the player's own machine, then
     * sends the request by UUID.
     *
     * Two reasons it works this way: the backend runs on Cloudflare and Mojang refuses
     * those requests, and filing by UUID means you can add someone who has never opened
     * River - the request simply waits until the first time they sign in.
     */
    fun addFriend(name: String, onDone: (Boolean, String) -> Unit) {
        if (!signedIn) { onDone(false, "Not signed in yet."); return }
        val client = Minecraft.getInstance()
        pool.submit {
            val uuid = resolveMojangUuid(name)
            if (uuid == null) {
                client.execute { onDone(false, "No Minecraft account called \"$name\".") }
                return@submit
            }
            val body = JsonObject().apply {
                addProperty("name", name)
                addProperty("uuid", uuid)
            }
            val payload = runCatching { post("/friends/request", body, token) }.getOrNull()
            client.execute { finish(payload, onDone) }
        }
    }

    /** Mojang's public name lookup. Reachable from a player's machine; blocked for Cloudflare. */
    private fun resolveMojangUuid(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val connection = (URL("https://api.mojang.com/users/profiles/minecraft/$clean")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "riverclient.xyz/river-client")
        }
        return try {
            // 204/404 means Mojang has no such account.
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            JsonParser.parseString(text).asJsonObject.get("id")?.asString
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun acceptRequest(uuid: String, onDone: (Boolean, String) -> Unit) =
        uuidAction("/friends/accept", uuid, onDone)

    fun declineRequest(uuid: String, onDone: (Boolean, String) -> Unit) =
        uuidAction("/friends/decline", uuid, onDone)

    fun removeFriend(uuid: String, onDone: (Boolean, String) -> Unit) =
        uuidAction("/friends/remove", uuid, onDone)

    fun block(uuid: String, onDone: (Boolean, String) -> Unit) = uuidAction("/block", uuid, onDone)

    fun unblock(uuid: String, onDone: (Boolean, String) -> Unit) = uuidAction("/unblock", uuid, onDone)

    /** Who you have blocked, so the friends screen can offer to undo it. */
    fun blockedList(onDone: (List<Friend>) -> Unit) {
        call("/blocked", JsonObject()) { payload ->
            onDone(payload?.getAsJsonArray("blocked")?.mapNotNull { element ->
                val obj = element.asJsonObject
                Friend(
                    uuid = obj.get("uuid")?.asString ?: return@mapNotNull null,
                    name = obj.get("name")?.asString ?: return@mapNotNull null,
                    online = false,
                    server = ""
                )
            } ?: emptyList())
        }
    }

    fun report(uuid: String, reason: String, onDone: (Boolean, String) -> Unit) {
        val body = JsonObject().apply {
            addProperty("uuid", uuid)
            addProperty("reason", reason)
        }
        call("/report", body) { payload -> finish(payload, onDone) }
    }

    private fun uuidAction(route: String, uuid: String, onDone: (Boolean, String) -> Unit) {
        val body = JsonObject().apply { addProperty("uuid", uuid) }
        call(route, body) { payload ->
            finish(payload, onDone)
            // The list always changes shape after one of these, so pull it fresh.
            if (payload?.get("ok")?.asBoolean == true) refresh(force = true)
        }
    }

    // ------------------------------------------------------------------ messages

    fun history(uuid: String, onDone: (List<Message>) -> Unit) {
        val body = JsonObject().apply { addProperty("uuid", uuid) }
        call("/dm/history", body) { payload ->
            onDone(payload?.getAsJsonArray("messages")?.mapNotNull(::parseMessage) ?: emptyList())
        }
    }

    fun send(uuid: String, text: String, onDone: (Boolean, String) -> Unit) {
        val body = JsonObject().apply {
            addProperty("uuid", uuid)
            addProperty("text", text)
        }
        call("/dm/send", body) { payload -> finish(payload, onDone) }
    }

    private fun parseMessage(element: com.google.gson.JsonElement): Message? {
        val obj = element.asJsonObject
        return Message(
            id = obj.get("id")?.asString ?: return null,
            from = obj.get("from")?.asString ?: return null,
            fromName = obj.get("fromName")?.asString.orEmpty(),
            text = obj.get("text")?.asString ?: return null,
            at = obj.get("at")?.asLong ?: 0L
        )
    }

    private fun finish(payload: JsonObject?, onDone: (Boolean, String) -> Unit) {
        val ok = payload?.get("ok")?.asBoolean == true
        onDone(ok, payload?.get("message")?.asString ?: "River is unreachable.")
    }

    // ------------------------------------------------------------------ transport

    /** Runs [route] off-thread and delivers the response on the client main thread. */
    private fun call(route: String, body: JsonObject, onDone: (JsonObject?) -> Unit) {
        if (!signedIn) { onDone(null); return }
        val client = Minecraft.getInstance()
        pool.submit {
            val payload = runCatching { post(route, body, token) }.getOrNull()
            client.execute { onDone(payload) }
        }
    }

    private fun post(route: String, body: JsonObject, bearer: String = ""): JsonObject? {
        val connection = (URL("$BASE$route").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "riverclient.xyz/river-client")
            if (bearer.isNotEmpty()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            // Errors carry a JSON body too, so read whichever stream is present.
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: return null
            JsonParser.parseString(text).asJsonObject
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
