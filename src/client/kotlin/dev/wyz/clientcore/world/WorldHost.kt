package dev.wyz.clientcore.world

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.util.HttpUtil
import net.minecraft.world.level.GameType
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shares the singleplayer world you are in, without port forwarding.
 *
 * Opens the world to LAN, then dials OUT to River's relay and keeps that control
 * connection open. Outbound is the whole point: home routers happily allow it, so nobody
 * has to touch NAT settings, and your home IP is never published - friends only ever see
 * the relay's address.
 *
 * Per inbound player the relay asks for a connection; this opens one socket back to the
 * relay and one to the local world, then pumps bytes between them. Everything runs on a
 * daemon pool so the render and tick loops are never blocked.
 */
object WorldHost {

    private const val RELAY_HOST_DEFAULT = "relay.riverclient.xyz"
    private const val CONTROL_PORT = 7000
    private const val DATA_PORT = 7001
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val PING_INTERVAL_MS = 30_000L

    enum class State { OFF, STARTING, ONLINE, ERROR }

    @Volatile var state: State = State.OFF
        private set
    /** What friends type to join, once the relay has published it. */
    @Volatile var address: String = ""
        private set
    @Volatile var error: String = ""
        private set
    @Volatile var localPort: Int = 0
        private set

    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "River-WorldHost").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var control: Socket? = null
    private val liveSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    fun canHost(client: Minecraft): Boolean = client.singleplayerServer != null && client.level != null

    /** Extra line under the address, e.g. what to fix when the router is behind another router. */
    @Volatile var advice: String = ""
        private set

    /**
     * Hosts through the router itself: open the world to LAN, then ask the router to
     * forward that port. No relay, no account, nothing to pay for - it just needs UPnP,
     * which most home routers have on.
     *
     * Deliberately honest about the cases it cannot fix. A router will happily accept a
     * mapping while sitting behind a second router, and CGNAT cannot be forwarded at all;
     * both end up as a clear explanation rather than an address that silently never works.
     */
    fun startUpnp(client: Minecraft) {
        if (running.get()) return
        val server = client.singleplayerServer ?: run {
            error = "Only a singleplayer world can be hosted."
            state = State.ERROR
            return
        }

        state = State.STARTING
        error = ""
        address = ""
        advice = ""

        // Pick a concrete free port the way vanilla's Open to LAN does. Passing 0 and
        // letting the OS choose does NOT work here: the server reports the port back as 0,
        // and a router refuses a mapping with a wildcard external port.
        val port = if (server.isPublished && server.port > 0) server.port else {
            val chosen = runCatching { HttpUtil.getAvailablePort() }.getOrDefault(0)
            if (chosen <= 0) {
                error = "Could not find a free port to host on."
                state = State.ERROR
                return
            }
            if (!server.publishServer(GameType.SURVIVAL, false, chosen)) {
                error = "Could not open this world to LAN."
                state = State.ERROR
                return
            }
            // Trust the server's own port if it reports one, else the port we asked for.
            if (server.port > 0) server.port else chosen
        }
        if (port <= 0) {
            error = "Could not open this world to LAN."
            state = State.ERROR
            return
        }
        localPort = port
        running.set(true)

        pool.submit {
            val outcome = runCatching { UpnpPortMapper.open(port) }.getOrElse {
                UpnpPortMapper.Outcome(UpnpPortMapper.Result.UNAVAILABLE, message = it.message ?: "UPnP failed.")
            }
            client.execute {
                when (outcome.result) {
                    UpnpPortMapper.Result.MAPPED -> {
                        address = "${outcome.externalIp}:$port"
                        state = State.ONLINE
                    }
                    UpnpPortMapper.Result.DOUBLE_NAT -> {
                        // The port really is open, just on an address the internet cannot
                        // reach - so say what to change rather than claiming success.
                        address = ""
                        error = "Your router is behind another router."
                        advice = outcome.message
                        state = State.ERROR
                        running.set(false)
                    }
                    UpnpPortMapper.Result.CGNAT -> {
                        error = "Your internet provider does not allow incoming connections."
                        advice = "Ask them for a public IP, or host through a relay instead."
                        state = State.ERROR
                        running.set(false)
                    }
                    UpnpPortMapper.Result.UNAVAILABLE -> {
                        error = outcome.message.ifEmpty { "Your router did not allow the port to be opened." }
                        // Only suggest enabling UPnP when nothing answered. If the router
                        // replied with a refusal, UPnP is plainly already on and telling
                        // them otherwise just contradicts the error right above it.
                        advice = if (outcome.gatewayFound) {
                            "Your router refused this. Try again, or forward port ${localPort} to this PC yourself."
                        } else {
                            "Turn UPnP on in your router settings, then try again."
                        }
                        state = State.ERROR
                        running.set(false)
                    }
                }
            }
        }
    }

    /** Closes the router mapping as well as any relay session. */
    fun stopUpnp() {
        val port = localPort
        stop()
        advice = ""
        if (port > 0) pool.submit { runCatching { UpnpPortMapper.close(port) } }
    }

    /**
     * Publishes the world to LAN if needed, then registers with the relay.
     * [name] is the address label to request, e.g. "wyz" -> wyz.riverclient.xyz.
     */
    fun start(client: Minecraft, name: String, relayHost: String = RELAY_HOST_DEFAULT, token: String = "") {
        if (running.get()) return
        val server = client.singleplayerServer ?: run {
            error = "Only a singleplayer world can be hosted."
            state = State.ERROR
            return
        }

        state = State.STARTING
        error = ""
        address = ""

        // Reuse the existing LAN port if the world is already open, so hosting twice in one
        // session does not strand the first port.
        val port = if (server.isPublished && server.port > 0) server.port else {
            // A concrete port, not 0: the server reports a wildcard back as 0, which then
            // fails downstream.
            val chosen = runCatching { HttpUtil.getAvailablePort() }.getOrDefault(0)
            if (chosen <= 0 || !server.publishServer(GameType.SURVIVAL, false, chosen)) {
                error = "Could not open this world to LAN."
                state = State.ERROR
                return
            }
            if (server.port > 0) server.port else chosen
        }
        localPort = port
        running.set(true)
        pool.submit { runControl(client, name, relayHost, token, port) }
    }

    fun stop() {
        running.set(false)
        state = State.OFF
        address = ""
        try { control?.close() } catch (_: Throwable) {}
        control = null
        synchronized(liveSockets) {
            for (socket in liveSockets) runCatching { socket.close() }
            liveSockets.clear()
        }
    }

    // ------------------------------------------------------------------ control channel

    private fun runControl(client: Minecraft, name: String, relayHost: String, token: String, port: Int) {
        try {
            val socket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(relayHost, CONTROL_PORT), CONNECT_TIMEOUT_MS)
            }
            control = socket

            val out = socket.getOutputStream()
            writeLine(out, JsonObject().apply {
                addProperty("type", "register")
                addProperty("token", token)
                addProperty("label", "world")
                if (name.isNotBlank()) addProperty("name", name)
            })

            // Keepalive so the relay does not time the session out mid-session.
            pool.submit {
                while (running.get()) {
                    Thread.sleep(PING_INTERVAL_MS)
                    if (!running.get()) break
                    runCatching { writeLine(out, JsonObject().apply { addProperty("type", "ping") }) }
                        .onFailure { return@submit }
                }
            }

            val input = socket.getInputStream()
            val buffer = StringBuilder()
            while (running.get()) {
                val line = readLine(input, buffer) ?: break
                val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                when (message.get("type")?.asString) {
                    "registered" -> {
                        val published = message.get("address")?.asString.orEmpty()
                        val host = message.get("host")?.asString.orEmpty()
                        val relayPort = message.get("port")?.asInt ?: 0
                        address = if (published.isNotEmpty()) published else "$host:$relayPort"
                        state = State.ONLINE
                    }
                    "connection" -> {
                        val id = message.get("id")?.asString ?: continue
                        pool.submit { bridgePlayer(relayHost, id, port) }
                    }
                    "error" -> {
                        error = message.get("message")?.asString ?: "The relay refused this session."
                        state = State.ERROR
                        running.set(false)
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (running.get()) {
                error = throwable.message ?: "Could not reach the River relay."
                state = State.ERROR
            }
        } finally {
            if (state != State.ERROR) state = State.OFF
            running.set(false)
            runCatching { control?.close() }
            control = null
        }
    }

    /** One inbound player: claim it on the relay, then wire it to the local world. */
    private fun bridgePlayer(relayHost: String, id: String, port: Int) {
        var relaySocket: Socket? = null
        var localSocket: Socket? = null
        try {
            relaySocket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(relayHost, DATA_PORT), CONNECT_TIMEOUT_MS)
            }
            writeLine(relaySocket.getOutputStream(), JsonObject().apply {
                addProperty("type", "data")
                addProperty("id", id)
            })

            localSocket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            }

            liveSockets.add(relaySocket)
            liveSockets.add(localSocket)

            val relay = relaySocket
            val local = localSocket
            pool.submit { pump(relay.getInputStream(), local.getOutputStream(), relay, local) }
            pump(local.getInputStream(), relay.getOutputStream(), relay, local)
        } catch (_: Throwable) {
            runCatching { relaySocket?.close() }
            runCatching { localSocket?.close() }
        } finally {
            liveSockets.remove(relaySocket)
            liveSockets.remove(localSocket)
        }
    }

    /** Copies until either end closes, then tears both down so no half-open socket lingers. */
    private fun pump(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: Throwable) {
            // Normal on disconnect.
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private fun writeLine(out: OutputStream, payload: JsonObject) {
        out.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Reads one newline-delimited JSON message. Bytes past the newline stay in [buffer]
     * for the next call, so a message split across reads is not lost.
     */
    private fun readLine(input: InputStream, buffer: StringBuilder): String? {
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline >= 0) {
                val line = buffer.substring(0, newline)
                buffer.delete(0, newline + 1)
                return line.trim()
            }
            val chunk = ByteArray(1024)
            val read = input.read(chunk)
            if (read < 0) return null
            buffer.append(String(chunk, 0, read, Charsets.UTF_8))
            if (buffer.length > 16384) return null
        }
    }
}
