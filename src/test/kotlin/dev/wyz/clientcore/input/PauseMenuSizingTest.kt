package dev.wyz.clientcore.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PauseMenuSizingTest {
    @Test fun `column and footer fit at every supported GUI scale`() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 854 to 480)) {
            for (guiScale in 1..6) for (content in listOf(160, 220, 290)) {
                val layout = PauseMenuSizing.layout(width / guiScale, height / guiScale, guiScale.toDouble(), content)
                assertTrue(layout.scale > 0 && layout.scale <= 1)
                assertTrue((content + 2 * layout.padding) * layout.scale <= height / guiScale - 16)
                assertTrue(layout.width >= 230)
            }
        }
    }
}
