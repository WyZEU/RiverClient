package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO

class ScreenshotModule : Module("screenshot", "Screenshot", "F9 to capture your screen", ModuleCategory.UTILITY, "camera", 8, 262) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Screenshot"))
        list.add(BoolSetting("Copy to clipboard", { mutableScreenshot().copyToClipboard }, { mutableScreenshot().copyToClipboard = it }))
        list.add(ActionSetting("Screenshots folder", "Open") {
            runCatching { Util.getPlatform().openPath(screenshotsDir(Minecraft.getInstance()).toPath()) }
        })
    }

    fun capture(client: Minecraft) {
        Screenshot.grab(client.gameDirectory, client.mainRenderTarget) { message ->
            client.execute {
                client.gui.chat.addMessage(Component.literal("[River] ").withStyle(ChatFormatting.AQUA).append(message))
                if (effectiveScreenshotCfg().copyToClipboard) {
                    copyNewestToClipboard(client)
                }
            }
        }
    }

    private fun screenshotsDir(client: Minecraft): File = File(client.gameDirectory, "screenshots")

    private fun copyNewestToClipboard(client: Minecraft) {
        // AWT clipboard works on Windows; failure just means no copy, never a crash.
        runCatching {
            val newest = screenshotsDir(client).listFiles { f -> f.extension == "png" }
                ?.maxByOrNull { it.lastModified() } ?: return
            val image = ImageIO.read(newest) ?: return
            val transferable = object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
                override fun getTransferData(flavor: DataFlavor): Any = image
            }
            Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
            client.gui.chat.addMessage(Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Copied to clipboard").withStyle(ChatFormatting.GRAY)))
        }
    }
}
