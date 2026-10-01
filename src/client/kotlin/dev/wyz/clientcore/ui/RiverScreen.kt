package dev.wyz.clientcore.ui

/** Marker for River Client screens so shared hooks (watermark, HUD) can skip them. */
interface RiverScreen {
    /**
     * Whether this screen runs at River's display-relative GUI scale cap (see
     * WindowGuiScaleMixin). True for menus, which are laid out for a fixed virtual size.
     * False for anything that has to match the in-game HUD pixel for pixel.
     */
    fun capsGuiScale(): Boolean = true
}
