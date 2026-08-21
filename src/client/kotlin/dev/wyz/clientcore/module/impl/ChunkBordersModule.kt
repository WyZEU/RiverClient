package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus

/** Toggles the vanilla chunk border overlay — the same renderer as F3+G. */
class ChunkBordersModule : Module("chunk_borders", "Chunk Borders", "Vanilla chunk border overlay", ModuleCategory.VISUAL, "border", 8, 352, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var lastWanted: Boolean? = null

    /** Called every tick regardless of enabled state so turning the module off restores vanilla. */
    fun sync(client: Minecraft) {
        val wanted = active && client.level != null
        if (wanted != lastWanted) {
            client.debugEntries.setStatus(
                DebugScreenEntries.CHUNK_BORDERS,
                if (wanted) DebugScreenEntryStatus.ALWAYS_ON else DebugScreenEntryStatus.NEVER
            )
            lastWanted = wanted
        }
    }
}
