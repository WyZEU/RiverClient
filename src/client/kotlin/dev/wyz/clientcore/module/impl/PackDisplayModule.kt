package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.RowsHudModule
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft

/**
 * The resource packs you have on, top of the list first, the way the pack screen orders
 * them. Only packs you added: vanilla, mods' own packs and River's are left out.
 */
class PackDisplayModule : RowsHudModule("pack_display", "Pack Display", "The resource packs you have on", "item:minecraft:painting", 8, 524) {

    override val multiLine: Boolean
        get() = true

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Packs"))
        list.add(BoolSetting("Include server packs", { flag("server_packs", false) }, { setFlag("server_packs", it) }))
    }

    // The selected packs only change when someone changes them, so they are read once a second.
    private var cachedAt = 0L
    private var cached: List<Pair<String, String>> = emptyList()

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < 1000L) return cached
        cachedAt = now
        val serverPacks = flag("server_packs", false)
        cached = runCatching {
            client.resourcePackRepository.selectedPacks
                .filter { pack -> pack.id.startsWith("file/") || (serverPacks && pack.id.startsWith("server")) }
                .reversed()
                .map { "" to it.title.string.removeSuffix(".zip") }
        }.getOrDefault(emptyList())
        return cached
    }

    override fun previewRows() = listOf("" to "Faithful 32x", "" to "Low Fire")
}
