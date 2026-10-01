package dev.wyz.clientcore.net

import com.google.gson.JsonParser
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.TranslatableContents
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
//? if >=1.21.5 {
import java.net.URI
//?}
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * [Copy] and [Share] on vanilla's "Saved screenshot as ..." line.
 *
 * Chat click events can only run a command, open something, or copy text, and every
 * copy - in every version River supports - ends in KeyboardHandler.setClipboard. So the
 * buttons are copy clicks carrying a one-time token, and KeyboardHandlerMixin hands any
 * copy of such a token here instead of putting it on the clipboard. The tokens are
 * random and only ever minted by this class, so a server cannot put a working button in
 * chat that would upload a file of its choosing.
 */
object ScreenshotShare {
    private const val MARKER = "river-screenshot:"
    private const val UPLOAD_URL = "https://updates.riverclient.xyz/screenshots"
    /** The worker takes up to 10 MB; a bigger PNG goes up as a JPEG instead. */
    private const val MAX_PNG_BYTES = 9L * 1024 * 1024

    private val files = HashMap<String, File>()
    private val pool = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "River screenshot share").apply { isDaemon = true }
    }

    /** Appends the buttons when [message] is vanilla's screenshot-saved line. */
    @JvmStatic
    fun decorate(message: Component): Component {
        val contents = message.contents as? TranslatableContents ?: return message
        if (contents.key != "screenshot.success") return message
        val name = (contents.args.firstOrNull() as? Component)?.string ?: return message
        val file = File(File(Minecraft.getInstance().gameDirectory, "screenshots"), name)
        if (!file.isFile) return message

        val token = UUID.randomUUID().toString()
        files[token] = file
        return message.copy()
            .append(Component.literal(" "))
            .append(button("Copy", "Copy the picture to your clipboard", "copy:$token", ChatFormatting.AQUA))
            .append(Component.literal(" "))
            .append(button("Share", "Upload it and copy a link anyone can open", "share:$token", ChatFormatting.GREEN))
    }

    private fun button(label: String, hover: String, action: String, color: ChatFormatting): MutableComponent =
        Component.literal("[$label]").withStyle { style ->
            style.withColor(color)
                .withClickEvent(copyClick("$MARKER$action"))
                .withHoverEvent(hoverText(Component.literal(hover)))
        }

    /** True when [text] was one of our buttons and has been handled instead of copied. */
    @JvmStatic
    fun handleClipboard(text: String?): Boolean {
        if (text == null || !text.startsWith(MARKER)) return false
        val action = text.removePrefix(MARKER).substringBefore(':')
        val file = files[text.substringAfterLast(':')]
        if (file == null) {
            say(Component.literal("That screenshot button has expired. Take the screenshot again.").withStyle(ChatFormatting.GRAY))
            return true
        }
        when (action) {
            "copy" -> copy(file)
            "share" -> share(file)
        }
        return true
    }

    private fun copy(file: File) {
        pool.submit {
            val ok = runCatching {
                // Minecraft runs with java.awt.headless=true, so AWT's clipboard throws there.
                // On Windows, Windows Forms puts the picture on the clipboard instead; the
                // path goes in through the environment so no quoting can break it.
                val os = System.getProperty("os.name").orEmpty()
                when {
                    os.startsWith("Windows") -> copyOnWindows(file)
                    os.contains("Linux") -> copyOnLinux(file)
                    else -> copyWithAwt(file)
                }
            }.isSuccess
            Minecraft.getInstance().execute {
                say(
                    if (ok) Component.literal("Screenshot copied to your clipboard.").withStyle(ChatFormatting.GRAY)
                    else Component.literal("Could not copy that screenshot.").withStyle(ChatFormatting.RED)
                )
            }
        }
    }

    private fun copyOnWindows(file: File) {
        val script = "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; " +
            "\$img = [System.Drawing.Image]::FromFile(\$env:RIVER_SCREENSHOT); " +
            "[System.Windows.Forms.Clipboard]::SetImage(\$img); \$img.Dispose()"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", script)
            .redirectErrorStream(true)
            .apply { environment()["RIVER_SCREENSHOT"] = file.absolutePath }
            .start()
        process.inputStream.readBytes()
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("timed out") }
        if (process.exitValue() != 0) error("powershell exited ${process.exitValue()}")
    }

    /**
     * Linux has no clipboard Java can reach from here, so the picture goes through the
     * tool the desktop already has: wl-copy on Wayland, xclip on X11. Each takes the file
     * directly, so nothing needs quoting. If neither is installed, AWT gets a last try.
     */
    private fun copyOnLinux(file: File) {
        val attempts = listOf(
            listOf("wl-copy", "--type", "image/png") to true,
            listOf("xclip", "-selection", "clipboard", "-t", "image/png", "-i", file.absolutePath) to false
        )
        for ((command, readsStdin) in attempts) {
            val ok = runCatching {
                val builder = ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                if (readsStdin) builder.redirectInput(file)
                val process = builder.start()
                // Both hand the picture to a background process that keeps serving it and
                // return at once, so a quick clean exit is success and a hang is not.
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return
        }
        copyWithAwt(file)
    }

    private fun copyWithAwt(file: File) {
        val image = ImageIO.read(file) ?: error("unreadable")
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }

    private fun share(file: File) {
        if (!RiverSocial.signedIn) {
            say(Component.literal("Signing in to River to share...").withStyle(ChatFormatting.GRAY))
            RiverSocial.ensureSession { ok ->
                if (ok) share(file)
                else say(Component.literal("Sharing needs a River sign-in: ${RiverSocial.error.ifEmpty { "it did not work" }}").withStyle(ChatFormatting.RED))
            }
            return
        }
        say(Component.literal("Uploading screenshot...").withStyle(ChatFormatting.GRAY))
        val token = RiverSocial.token
        pool.submit {
            val result = runCatching { upload(file, token) }
            Minecraft.getInstance().execute {
                result.onSuccess { url ->
                    Minecraft.getInstance().keyboardHandler.setClipboard(url)
                    say(
                        Component.literal("Link copied: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(url).withStyle { style ->
                                style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                                    .withClickEvent(urlClick(url))
                                    .withHoverEvent(hoverText(Component.literal("Open in your browser")))
                            })
                    )
                }.onFailure {
                    say(Component.literal("Could not share: ${it.message ?: "upload failed"}").withStyle(ChatFormatting.RED))
                }
            }
        }
    }

    /** Uploads [file] and returns its share link. Runs off the render thread. */
    private fun upload(file: File, token: String): String {
        var bytes = file.readBytes()
        var type = "image/png"
        if (bytes.size > MAX_PNG_BYTES) {
            bytes = toJpeg(ImageIO.read(file) ?: error("that screenshot could not be read"))
            type = "image/jpeg"
        }
        val connection = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 30000
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", type)
            setRequestProperty("User-Agent", "riverclient.xyz/river-client")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: error("no answer from River")
            val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: error("River answered ${connection.responseCode}")
            // A stale session: drop it so the next Share signs in fresh.
            if (connection.responseCode == 401) RiverSocial.signOut()
            if (payload.get("ok")?.asBoolean != true) error(payload.get("message")?.asString ?: "River answered ${connection.responseCode}")
            return payload.get("url")?.asString ?: error("River sent no link")
        } finally {
            connection.disconnect()
        }
    }

    private fun toJpeg(source: BufferedImage): ByteArray {
        // JPEG has no alpha; drawing onto an RGB canvas drops it instead of failing.
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(source, 0, 0, null); dispose() }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.92f
            }
            writer.write(null, IIOImage(rgb, null, null), params)
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun say(text: Component) {
        Minecraft.getInstance().gui.chat.addMessage(
            Component.literal("[River] ").withStyle(ChatFormatting.AQUA).append(text)
        )
    }

    private fun copyClick(value: String): ClickEvent {
//? if >=1.21.5 {
        return ClickEvent.CopyToClipboard(value)
//?} else {
/*        return ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value)
*///?}
    }

    private fun urlClick(url: String): ClickEvent {
//? if >=1.21.5 {
        return ClickEvent.OpenUrl(URI.create(url))
//?} else {
/*        return ClickEvent(ClickEvent.Action.OPEN_URL, url)
*///?}
    }

    private fun hoverText(text: Component): HoverEvent {
//? if >=1.21.5 {
        return HoverEvent.ShowText(text)
//?} else {
/*        return HoverEvent(HoverEvent.Action.SHOW_TEXT, text)
*///?}
    }
}
