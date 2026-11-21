package com.past9.phoneaos

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs scripts/check-translations.py and scripts/test_check_translations.py. The first holds every values-* folder
 * to the English one (same keys, placeholders, HTML, escaping, plural categories, no English left); the second
 * proves the checker goes red on planted bad files. Skipped only when no working Python is installed.
 */
class TranslationsCheckTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "scripts/check-translations.py").isFile }
    /** A Python that really runs. On Windows "python" can be the Microsoft Store alias, so try py -3 too. */
    private fun pythonCommand(): List<String>? {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val candidates = listOfNotNull(
            System.getenv("PYTHON")?.takeIf { it.isNotBlank() }?.let { listOf(it) },
            listOf("python3"),
            if (windows) listOf("py", "-3") else null,
            listOf("python"),
        )
        return candidates.firstOrNull { command ->
            runCatching {
                val p = ProcessBuilder(command + "--version").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText(); p.waitFor() == 0
            }.getOrDefault(false)
        }
    }

    private fun python(vararg args: String): Pair<Int, String>? {
        val command = pythonCommand() ?: return null
        return try {
            val p = ProcessBuilder(command + listOf("-I", "-X", "utf8") + args).directory(root)
                .redirectErrorStream(true).apply { environment()["PYTHONIOENCODING"] = "utf-8" }.start()
            val out = p.inputStream.reader(Charsets.UTF_8).readText(); p.waitFor() to out
        } catch (e: java.io.IOException) { null }
    }

    @Test fun everyTranslationMatchesTheEnglishStrings() {
        val r = python("scripts/check-translations.py", "--quiet"); assumeTrue("python not installed", r != null)
        assertEquals(r!!.second, 0, r.first)
    }

    @Test fun theCheckerGoesRedOnBadFiles() {
        val r = python("scripts/test_check_translations.py"); assumeTrue("python not installed", r != null)
        assertEquals(r!!.second, 0, r.first)
    }
}
