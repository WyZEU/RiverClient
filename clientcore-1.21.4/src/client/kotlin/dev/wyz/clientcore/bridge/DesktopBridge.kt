package dev.wyz.clientcore.bridge

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object DesktopBridge {
    private const val PRESENCE_REFRESH_MS = 2000L
    private val gson = GsonBuilder().create()
    private val bridgeDir: Path by lazy {
        val appData = System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming")
        appData.resolve("River Client").resolve("bridge")
    }
    private val heartbeatPath: Path by lazy { bridgeDir.resolve("heartbeat.json") }
    private val commandPath: Path by lazy { bridgeDir.resolve("command.json") }
    private val statePath: Path by lazy { bridgeDir.resolve("state.json") }
    private var lastStateKey: String = ""
    private var lastStateWriteAt: Long = 0L

    fun isDesktopOverlayAvailable(): Boolean {
        return runCatching {
            if (!heartbeatPath.exists()) return false
            val raw = Files.readString(heartbeatPath)
            val payload = gson.fromJson(raw, Heartbeat::class.java) ?: return false
            System.currentTimeMillis() - payload.timestamp <= 4000L
        }.getOrDefault(false)
    }

    fun requestMenuToggle(): Boolean {
        return requestCommand("toggle-menu")
    }

    fun requestSocialToggle(): Boolean {
        return requestCommand("toggle-social")
    }

    private fun requestCommand(type: String): Boolean {
        return runCatching {
            bridgeDir.createDirectories()
            val payload = Command(
                type = type,
                timestamp = System.currentTimeMillis()
            )
            Files.writeString(
                commandPath,
                gson.toJson(payload),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            true
        }.getOrDefault(false)
    }

    fun pushPresenceState(
        state: String,
        serverName: String? = null,
        serverAddress: String? = null,
        serverIconBase64: String? = null
    ) {
        runCatching {
            bridgeDir.createDirectories()
            val now = System.currentTimeMillis()
            val payload = PresenceState(
                state = state,
                serverName = serverName?.takeIf { it.isNotBlank() },
                serverAddress = serverAddress?.takeIf { it.isNotBlank() },
                serverIconBase64 = serverIconBase64?.takeIf { it.isNotBlank() },
                timestamp = now
            )
            val stateKey = buildString {
                append(payload.state)
                append('|')
                append(payload.serverName ?: "")
                append('|')
                append(payload.serverAddress ?: "")
                append('|')
                append(payload.serverIconBase64 ?: "")
            }
            if (stateKey == lastStateKey && now - lastStateWriteAt < PRESENCE_REFRESH_MS) return
            val json = gson.toJson(payload)
            Files.writeString(
                statePath,
                json,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            lastStateKey = stateKey
            lastStateWriteAt = now
        }
    }

    private data class Heartbeat(
        val timestamp: Long = 0L
    )

    private data class Command(
        val type: String,
        val timestamp: Long
    )

    private data class PresenceState(
        val state: String,
        val serverName: String? = null,
        val serverAddress: String? = null,
        val serverIconBase64: String? = null,
        val timestamp: Long
    )
}
