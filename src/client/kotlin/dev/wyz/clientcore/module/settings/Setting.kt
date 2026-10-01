package dev.wyz.clientcore.module.settings

/**
 * Declarative setting descriptors. Modules expose a list of these and the UI
 * (slide-out panel in the menu, mini panel in the HUD editor) renders matching
 * controls. Values are read/written through the closures so settings always
 * reflect the active profile.
 */
sealed class Setting(val label: String)

/** Group header inside a settings panel. */
class SectionSetting(label: String) : Setting(label)

class BoolSetting(
    label: String,
    val get: () -> Boolean,
    val set: (Boolean) -> Unit
) : Setting(label)

class IntSetting(
    label: String,
    val min: Int,
    val max: Int,
    val get: () -> Int,
    val set: (Int) -> Unit,
    val suffix: String = ""
) : Setting(label)

/** Dropdown. [get] must return one of [options]. */
class ChoiceSetting(
    label: String,
    val options: List<String>,
    val get: () -> String,
    val set: (String) -> Unit
) : Setting(label)

/** Color picker over an ARGB int. When [hasAlpha] is false the alpha channel is kept at 255. */
class ColorSetting(
    label: String,
    val hasAlpha: Boolean,
    val get: () -> Int,
    val set: (Int) -> Unit
) : Setting(label)

/** GLFW key code, -1 = unbound. The UI captures the next key press. */
class KeybindSetting(
    label: String,
    val get: () -> Int,
    val set: (Int) -> Unit
) : Setting(label)

/** A one-shot button row (e.g. "Add waypoint", "Remove"). */
class ActionSetting(
    label: String,
    val buttonLabel: String,
    val destructive: Boolean = false,
    val action: () -> Unit
) : Setting(label)
