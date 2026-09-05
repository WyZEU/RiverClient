package dev.wyz.clientcore.config

data class ClientCoreConfig(
    val modules: MutableMap<String, ModuleConfig> = mutableMapOf(),
    /** Accent theme key (see RiverTheme presets), or "custom". */
    var theme: String? = "river",
    /** Gradient start hex (0xRRGGBB) used when [theme] is "custom". */
    var themeCustom: Int = 0x7A8CFF,
    /** Gradient end hex (0xRRGGBB) used when [theme] is "custom". Default matches
     *  the color the old single-color custom theme auto-derived from the start. */
    var themeCustomB: Int = 0xAD85FF,
    /** Cinematic mode: hides every module and the watermark for clean footage. */
    var cinematicMode: Boolean = false,
    /** Watermark screen corner: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right. */
    var watermarkCorner: Int = 3,
    /** GLFW key that toggles cinematic mode without opening the menu, -1 = unbound. */
    var cinematicKey: Int = -1,
    /**
     * Left Shift + Tab opens the friends menu. Separately switchable per context because
     * that is also sneak + player list: on a server it fires during ordinary play, while
     * in singleplayer the player list is meaningless and the shortcut is free.
     */
    var friendsKeyInSingleplayer: Boolean = true,
    var friendsKeyOnServers: Boolean = true,
    /** GLFW key for the friends menu, held with Left Shift. -1 uses the default (Tab). */
    var friendsKey: Int = -1,
    /** Toast when a friend messages you while you are playing. */
    var friendsMessageToasts: Boolean = true,
    /** Let friends see which server you are on. Off by default - presence hashes it otherwise. */
    var friendsShareServer: Boolean = false,
    /** Module ids starred in the menu. */
    var favorites: MutableList<String>? = null,
    /** Server key -> client-side waypoints saved for that server/world. */
    var waypoints: MutableMap<String, MutableList<WaypointData>>? = null
) {
    fun favoritesList(): MutableList<String> {
        if (favorites == null) favorites = mutableListOf()
        return favorites!!
    }

    fun waypointsMap(): MutableMap<String, MutableList<WaypointData>> {
        if (waypoints == null) waypoints = mutableMapOf()
        return waypoints!!
    }
}

data class WaypointData(
    var name: String = "Waypoint",
    var x: Int = 0,
    var y: Int = 64,
    var z: Int = 0,
    var dimension: String = "minecraft:overworld",
    /** Index into the shared waypoint color palette. */
    var color: Int = 0,
    /**
     * Hidden waypoints keep their position and stay in the manager, they just stop
     * rendering in the world - so you can silence a marker without losing the spot.
     * Defaults false so every existing saved waypoint stays visible after updating.
     */
    var hidden: Boolean = false
)

data class SharedHudStyle(
    var showBackground: Boolean = true,
    var backgroundOpacity: Int = 130,
    var showBorder: Boolean = false,
    var borderOpacity: Int = 160,
    var borderThickness: Int = 1,
    var padding: Int = 5,
    var spacing: Int = 2,
    var textShadow: Boolean = true,
    var layoutHorizontal: Boolean = true,
    /** Custom text color as 0xRRGGBB, or -1 to use the theme default. */
    var textColor: Int = -1,
    /** Custom accent color as 0xRRGGBB, or -1 to use the theme default. */
    var accentColor: Int = -1
)

data class CrosshairSettings(
    var hideVanillaCrosshair: Boolean = true,
    var swapOnTarget: Boolean = true,
    var normalPreset: String = "custom",
    var targetPreset: String = "classic",
    var showTop: Boolean = true,
    var showBottom: Boolean = true,
    var showLeft: Boolean = true,
    var showRight: Boolean = true,
    var showCenterDot: Boolean = true,
    var useOutline: Boolean = true,
    var dynamicAttackGap: Boolean = false,
    var tShape: Boolean = false,
    var gap: Int = 4,
    var length: Int = 6,
    var thickness: Int = 2,
    var dotSize: Int = 2,
    var outlineThickness: Int = 1,
    var red: Int = 255,
    var green: Int = 255,
    var blue: Int = 255,
    var alpha: Int = 255,
    var outlineRed: Int = 18,
    var outlineGreen: Int = 18,
    var outlineBlue: Int = 18,
    var outlineAlpha: Int = 210
)

data class CombatVisualsSettings(
    var lowFire: Boolean = true,
    var lowShield: Boolean = true,
    var lowTotemWarning: Boolean = true,
    var lowTotemThreshold: Int = 1,
    var warningSound: Boolean = true,
    var warningIntervalTicks: Int = 35
)

data class InventoryHudSettings(
    var showTitle: Boolean = true,
    var slotScalePercent: Int = 100,
    var showSlotBackgrounds: Boolean = true,
    var compactCountText: Boolean = false
)

data class ArmorHudSettings(
    var slotSize: Int = 20,
    var slotSpacing: Int = 1,
    var slotBackgroundOpacity: Int = 200,
    var borderOpacity: Int = 200,
    var borderThickness: Int = 1,
    var cornerRadius: Int = 0,
    var showDurability: Boolean = true,
    var durabilityThickness: Int = 2,
    /** Small remaining-durability number above the bar (hotbar-style HUD). */
    var showDurabilityNumbers: Boolean = true,
    /** Draw the durability numbers on the left side of the icons instead of the right. */
    var durabilityNumbersLeft: Boolean = true,
    /** "gold" = solid RuneScape-style gold (red when critical); "durability" = colour-graded by remaining %. */
    var numberStyle: String = "gold",
    var showEmptySlots: Boolean = true,
    var showOuterBackground: Boolean = true,
    var outerBackgroundOpacity: Int = 100,
    var itemIconScalePercent: Int = 100,
    /** When remaining durability is at or below this percent, show a strong visual warning. */
    var warnDurabilityPercent: Int = 20,
    var warnVisual: Boolean = true,
    /** Play a short UI sound while any armor piece is in the warn zone (see interval). */
    var warnSound: Boolean = false,
    /** Minimum ticks between warning sounds while still low (20 = 1s). */
    var warnSoundIntervalTicks: Int = 35
)

data class NameTagSettings(
    var showRiverBadge: Boolean = true,
    var textShadow: Boolean = false
)

data class ZoomSettings(
    /** GLFW key held to zoom. Default: C. */
    var holdKey: Int = 67,
    var zoomFov: Int = 30
)

data class FreelookSettings(
    /** GLFW key held to freelook. Default: Left Alt. */
    var holdKey: Int = 342
)

data class ToggleSprintSettings(
    var showIndicator: Boolean = true
)

data class WaypointModuleSettings(
    var showWorldMarkers: Boolean = true,
    var deathWaypoints: Boolean = true,
)

data class ScoreboardSettings(
    var hideAll: Boolean = false,
    var hideNumbers: Boolean = true,
    var scalePercent: Int = 100
)

data class ChatTweaksSettings(
    var timestamps: Boolean = true,
    var mentionSound: Boolean = true,
    var longerHistory: Boolean = true
)

data class ScreenshotSettings(
    var copyToClipboard: Boolean = false
)

data class PingSettings(
    var showInTab: Boolean = true
)

data class PotionHudSettings(
    /** "icons" (vanilla effect sprites + timer) or "text" (name + timer list). */
    var displayMode: String = "icons",
    var showTimer: Boolean = true,
    var showAmplifier: Boolean = true,
    /** Hide the vanilla effect icons in the top-right corner while this module runs. */
    var hideVanilla: Boolean = true,
    /** Blink expiring effects — faster the closer they are to running out. */
    var blinkExpiring: Boolean = true
)

data class ModuleConfig(
    var enabled: Boolean = true,
    var x: Int = 8,
    var y: Int = 8,
    /** False = position is managed by the auto-stacking HUD layout; true = user placed it. */
    var placed: Boolean = false,
    /**
     * Anchored position for placed elements: which screen third the element belongs to
     * (0 = left/top, 1 = center, 2 = right/bottom; -1 = not captured yet, legacy absolute
     * x/y) plus the offset from that anchor. Anchors make dragged layouts survive
     * resolution and GUI-scale changes - raw x/y did not.
     */
    var anchorH: Int = -1,
    var anchorV: Int = -1,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var keybind: Int = -1,
    var scalePercent: Int = 100,
    var style: SharedHudStyle? = null,
    var armorHud: ArmorHudSettings? = null,
    var nameTag: NameTagSettings? = null,
    var crosshair: CrosshairSettings? = null,
    var combatVisuals: CombatVisualsSettings? = null,
    var inventoryHud: InventoryHudSettings? = null,
    var zoom: ZoomSettings? = null,
    var freelook: FreelookSettings? = null,
    var toggleSprint: ToggleSprintSettings? = null,
    var waypointsCfg: WaypointModuleSettings? = null,
    var potionHud: PotionHudSettings? = null,
    var scoreboard: ScoreboardSettings? = null,
    var chatTweaks: ChatTweaksSettings? = null,
    var screenshot: ScreenshotSettings? = null,
    var ping: PingSettings? = null,
    /** Generic per-module scalar slots for simple modules (ints; bools stored as 0/1). */
    var scalars: MutableMap<String, Int>? = null
) {
    fun scalarMap(): MutableMap<String, Int> {
        if (scalars == null) scalars = mutableMapOf()
        return scalars!!
    }

    fun effectiveStyle(): SharedHudStyle = style ?: SharedHudStyle()
    fun effectiveArmorHud(): ArmorHudSettings = armorHud ?: ArmorHudSettings()
    fun effectiveNameTag(): NameTagSettings = nameTag ?: NameTagSettings()
    fun effectiveCrosshair(): CrosshairSettings = crosshair ?: CrosshairSettings()
    fun effectiveCombatVisuals(): CombatVisualsSettings = combatVisuals ?: CombatVisualsSettings()
    fun effectiveInventoryHud(): InventoryHudSettings = inventoryHud ?: InventoryHudSettings()
    fun effectiveZoom(): ZoomSettings = zoom ?: ZoomSettings()
    fun effectiveFreelook(): FreelookSettings = freelook ?: FreelookSettings()
    fun effectiveToggleSprint(): ToggleSprintSettings = toggleSprint ?: ToggleSprintSettings()
    fun effectiveWaypoints(): WaypointModuleSettings = waypointsCfg ?: WaypointModuleSettings()
    fun effectivePotionHud(): PotionHudSettings = potionHud ?: PotionHudSettings()
    fun effectiveScoreboard(): ScoreboardSettings = scoreboard ?: ScoreboardSettings()
    fun effectiveChatTweaks(): ChatTweaksSettings = chatTweaks ?: ChatTweaksSettings()
    fun effectiveScreenshot(): ScreenshotSettings = screenshot ?: ScreenshotSettings()
    fun effectivePing(): PingSettings = ping ?: PingSettings()
}
