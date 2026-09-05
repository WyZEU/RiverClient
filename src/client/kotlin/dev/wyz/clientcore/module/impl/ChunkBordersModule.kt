package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus
//?} else {
/**///?}

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
//? if >=1.21.11 {
            client.debugEntries.setStatus(
                DebugScreenEntries.CHUNK_BORDERS,
                if (wanted) DebugScreenEntryStatus.ALWAYS_ON else DebugScreenEntryStatus.NEVER
            )
//?} else {
/*            // 1.21.4's DebugRenderer only exposes a toggle (no direct setter, and the field
            // itself is private), so this only flips on actual transitions - which lastWanted
            // already guarantees.
            client.debugRenderer.switchRenderChunkborder()
*///?}
            lastWanted = wanted
        }
    }
}
