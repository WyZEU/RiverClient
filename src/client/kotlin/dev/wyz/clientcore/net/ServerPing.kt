package dev.wyz.clientcore.net

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Hashtable
import javax.naming.directory.InitialDirContext

/**
 * Asks a Minecraft server how it is doing, the way the multiplayer screen does: player
 * count, message of the day, version and icon, plus how long a round trip takes.
 *
 * Written against the protocol rather than the game's own pinger because that class has
 * changed shape between versions River supports, and the status handshake has not
 * changed in a decade. Blocking: call it off the render thread.
 */
object ServerPing {

    data class Result(
        val online: Int,
        val max: Int,
        val motd: String,
        val version: String,
        val pingMs: Long,
        val favicon: ByteArray?
    )

    private const val TIMEOUT_MS = 5000

    fun ping(address: String): Result {
        val (host, port) = resolve(address)
        Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Handshake: protocol -1 (just asking), the address as typed, next state status.
            val hostName = address.substringBefore(':').trim()
            writePacket(out) {
                writeVarInt(it, 0x00)
                writeVarInt(it, -1)
                writeString(it, hostName)
                it.writeShort(port)
                writeVarInt(it, 1)
            }
            writePacket(out) { writeVarInt(it, 0x00) }

            readVarInt(input)
            if (readVarInt(input) != 0x00) error("Unexpected status reply")
            val json = String(ByteArray(readVarInt(input)).also { input.readFully(it) }, Charsets.UTF_8)

            val sent = System.nanoTime()
            writePacket(out) {
                writeVarInt(it, 0x01)
                it.writeLong(sent)
            }
            readVarInt(input)
            readVarInt(input)
            input.readLong()
            val pingMs = (System.nanoTime() - sent) / 1_000_000

            val root = JsonParser.parseString(json).asJsonObject
            val players = root.getAsJsonObject("players")
            val favicon = root.get("favicon")?.takeIf { it.isJsonPrimitive }?.asString
                ?.substringAfter("base64,", "")
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Base64.getDecoder().decode(it.replace("\n", "")) }.getOrNull() }
            return Result(
                online = players?.get("online")?.asInt ?: 0,
                max = players?.get("max")?.asInt ?: 0,
                motd = flatten(root.get("description")).replace(Regex("§."), "").trim(),
                version = root.getAsJsonObject("version")?.get("name")?.asString.orEmpty().replace(Regex("§."), ""),
                pingMs = pingMs,
                favicon = favicon
            )
        }
    }

    /** host:port, following the _minecraft._tcp SRV record when no port was given. */
    private fun resolve(address: String): Pair<String, Int> {
        val trimmed = address.trim()
        val explicitPort = trimmed.substringAfterLast(':', "").toIntOrNull()
        if (explicitPort != null) return trimmed.substringBeforeLast(':') to explicitPort
        runCatching {
            val env = Hashtable<String, String>().apply {
                put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
                put("com.sun.jndi.dns.timeout.initial", "2000")
                put("com.sun.jndi.dns.timeout.retries", "1")
            }
            val record = InitialDirContext(env).getAttributes("dns:_minecraft._tcp.$trimmed", arrayOf("SRV"))
                .get("SRV")?.get()?.toString()
            if (record != null) {
                // "priority weight port target."
                val parts = record.split(" ")
                if (parts.size == 4) return parts[3].removeSuffix(".") to parts[2].toInt()
            }
        }
        return trimmed to 25565
    }

    /** A chat component's plain text: a string, or { text, extra: [...] } nested any deep. */
    private fun flatten(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (element.isJsonArray) return element.asJsonArray.joinToString("") { flatten(it) }
        val obj: JsonObject = element.asJsonObject
        val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val extra = obj.getAsJsonArray("extra")?.joinToString("") { flatten(it) }.orEmpty()
        return text + extra
    }

    private fun writePacket(out: DataOutputStream, body: (DataOutputStream) -> Unit) {
        val buffer = ByteArrayOutputStream()
        body(DataOutputStream(buffer))
        val bytes = buffer.toByteArray()
        writeVarInt(out, bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) error("VarInt too long")
        }
    }
}
