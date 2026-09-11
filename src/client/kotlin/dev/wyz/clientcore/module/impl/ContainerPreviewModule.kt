package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * Shows what is inside a container without opening it.
 *
 * Vanilla lists the first few stacks as text, which is no help with the thing people
 * actually use shulkers for - finding the one with the blocks in it - so while this is on,
 * that list is replaced rather than added to.
 *
 * Nothing here happens with the module off. Every tooltip path asks this first, so
 * switching it off returns every tooltip to exactly what vanilla built.
 *
 * The drawing lives in ui/tooltip. This is the switch and its settings.
 */
class ContainerPreviewModule : Module(
    "container_preview", "Container Preview", "See inside shulkers without opening them",
    ModuleCategory.UTILITY, "box", 8, 8, false
) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var enderChests = true
    private var requireShift = true
    private var showCounts = true
    private var tintBackground = true
    private var maxRows = 3
    private var slotSpacing = 0
    private var mergeDuplicates = true
    private var scalePercent = 100

    /** All read from the tooltip path, which runs outside this module. */
    val previewsEnderChests: Boolean get() = enderChests
    val needsShift: Boolean get() = requireShift
    val drawsCounts: Boolean get() = showCounts
    val drawsTint: Boolean get() = tintBackground
    val rowLimit: Int get() = maxRows
    val spacing: Int get() = slotSpacing
    val mergesDuplicates: Boolean get() = mergeDuplicates
    val scale: Int get() = scalePercent

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Preview"))
        list.add(BoolSetting("Ender chests", { enderChests }, { enderChests = it }))
        list.add(BoolSetting("Hold shift", { requireShift }, { requireShift = it }))

        list.add(SectionSetting("Grid"))
        /*
          On by default. A shulker of cobblestone is twenty-seven cells of the same
          texture, which says nothing the first cell did not; merged, the grid is as long
          as the number of different things in the box.
        */
        list.add(BoolSetting("Merge duplicates", { mergeDuplicates }, { mergeDuplicates = it }))
        list.add(BoolSetting("Item counts", { showCounts }, { showCounts = it }))
        list.add(BoolSetting("Dye tint", { tintBackground }, { tintBackground = it }))
        // A shulker is three rows, but two is enough to recognise one at a glance and
        // leaves more of the screen visible.
        list.add(IntSetting("Max rows", 1, 3, { maxRows }, { maxRows = it }))
        /*
          Zero reads as a vanilla inventory, where the cells touch and the grid is one
          block. Spacing them makes each item easier to pick out on its own, at the cost
          of a wider tooltip.
        */
        list.add(IntSetting("Slot spacing", 0, 4, { slotSpacing }, { slotSpacing = it }, "px"))
        /*
          Shrinks the whole grid. A full shulker at 100% is three rows of nine, which is a
          lot to drop over the screen every time you check one; at 60% it is still readable
          and takes a third of the space.
        */
        list.add(IntSetting("Size", 50, 100, { scalePercent }, { scalePercent = it }, "%"))
    }
}
