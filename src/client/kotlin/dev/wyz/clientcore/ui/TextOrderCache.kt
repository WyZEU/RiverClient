package dev.wyz.clientcore.ui

import net.minecraft.locale.Language
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.FormattedCharSequence

/**
 * Remembers the display order of recently drawn plain strings.
 *
 * From 1.21.6 drawing a String runs it through the Unicode bidi algorithm (ICU) on every
 * call, every frame, even when it is plain English that has not changed since the last
 * frame. HUD text is exactly that: "FPS", "W", "Biome", a clock. Vanilla already caches
 * the result for Components; this does the same for Strings (see TextOrderCacheMixin).
 *
 * Render thread only, like all GUI drawing, so it needs no locking. Bounded, so text that
 * changes every frame (coordinates, timers) only cycles through the oldest entries.
 * Cleared when the language changes, since that is what the order depends on.
 */
object TextOrderCache {

    private const val MAX_ENTRIES = 512
    private const val MAX_LENGTH = 160

    private var language: Language? = null

    private val entries = object : LinkedHashMap<String, FormattedCharSequence>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FormattedCharSequence>?): Boolean =
            size > MAX_ENTRIES
    }

    @JvmStatic
    fun visualOrder(language: Language, raw: String?, text: FormattedText): FormattedCharSequence {
        if (raw == null || raw.length > MAX_LENGTH) return language.getVisualOrder(text)
        if (language !== this.language) {
            entries.clear()
            this.language = language
        }
        entries[raw]?.let { return it }
        val order = language.getVisualOrder(text)
        entries[raw] = order
        return order
    }
}
