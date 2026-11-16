package dev.wyz.clientcore.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ProfileNamesTest {
    @TempDir
    lateinit var profiles: Path

    private fun available(base: String): String {
        val checked = mutableSetOf<String>()
        return ProfileNames.available(base) { name ->
            // Fail immediately if a regression keeps testing the same truncated name.
            assertTrue(checked.add(name), "Repeated collision candidate: $name")
            assertTrue(name.length <= 24, "Profile name exceeds the filesystem contract")
            Files.exists(profiles.resolve("$name.json"))
        }
    }

    private fun write(name: String, contents: String = "existing profile") {
        Files.writeString(profiles.resolve("$name.json"), contents, StandardOpenOption.CREATE_NEW)
    }

    @Test
    fun keepsAnUnusedName() {
        assertEquals("My Profile", available("My Profile"))
        assertEquals("abcdefghijklmnopqrstuvwx", available("abcdefghijklmnopqrstuvwx"))
    }

    @Test
    fun shortNameCollisionsKeepTheExistingNumbering() {
        write("My Profile")
        write("My Profile 2")
        assertEquals("My Profile 3", available("My Profile"))
    }

    @Test
    fun namesAtAndNearTheLimitRetainTheirSuffix() {
        for (base in listOf("a".repeat(23), "b".repeat(24))) {
            write(base, "original bytes")
            val imported = available(base)
            assertEquals(base.take(22) + " 2", imported)
            write(imported, "imported bytes")
            assertEquals("original bytes", Files.readString(profiles.resolve("$base.json")))
        }
    }

    @Test
    fun repeatedImportsRemainDistinctAsTheSuffixGrows() {
        val base = "abcdefghijklmnopqrstuvwx"
        val imported = (1..105).map { number ->
            available(base).also { write(it, "profile $number") }
        }
        assertEquals("abcdefghijklmnopqrstuv 2", imported[1])
        assertEquals("abcdefghijklmnopqrstu 10", imported[9])
        assertEquals("abcdefghijklmnopqrst 100", imported[99])
        assertEquals(105, imported.toSet().size)
        imported.forEachIndexed { index, name ->
            assertEquals("profile ${index + 1}", Files.readString(profiles.resolve("$name.json")))
        }
    }

    @Test
    fun truncationDoesNotLeaveExtraWhitespaceBeforeTheSuffix() {
        val base = "abcdefghijklmnopqrstu XY"
        write(base)
        assertEquals("abcdefghijklmnopqrstu 2", available(base))
    }
}
