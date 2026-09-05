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

/**
 * Toggles the vanilla entity hitbox renderer — exactly what F3+B shows, nothing more.
 * No custom colors, no target highlighting, no through-wall rendering.
 */
class HitboxesModule : Module("hitboxes", "Hitboxes", "Vanilla hitboxes, F3+B style", ModuleCategory.VISUAL, "box", 8, 334, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var lastWanted: Boolean? = null

    /** Called every tick regardless of enabled state so turning the module off restores vanilla. */
    fun sync(client: Minecraft) {
        val wanted = active && client.level != null
        if (wanted != lastWanted) {
//? if >=1.21.11 {
            // Only flip on transitions so manual F3+B still works while we're off.
            client.debugEntries.setStatus(
                DebugScreenEntries.ENTITY_HITBOXES,
                if (wanted) DebugScreenEntryStatus.ALWAYS_ON else DebugScreenEntryStatus.NEVER
            )
//?} else {
/*            client.entityRenderDispatcher.setRenderHitBoxes(wanted)
*///?}
            lastWanted = wanted
        }
    }
}
