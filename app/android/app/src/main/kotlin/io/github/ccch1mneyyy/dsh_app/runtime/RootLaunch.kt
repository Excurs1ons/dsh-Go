package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Whether this phone can start the runtime as root, and whether the person authorized it.
 *
 * [detected] is a plain file check and stays cheap enough for every status emit. [probe]
 * runs `su`, which may show the root manager's authorization prompt, so only an explicit
 * user action calls it — never a start, never a status refresh.
 */
object RootLaunch {
    /** Where root managers install their `su`. */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/debug_ramdisk/su",
    )

    /** Wire values are part of the status map; keep them stable. */
    enum class State(val wire: String) {
        UNKNOWN("unknown"),
        UNAVAILABLE("unavailable"),
        AUTHORIZED("authorized"),
        DENIED("denied"),
        TIMEOUT("timeout"),
    }

    data class Probe(val state: State, val detail: String? = null)

    /** Longer than any reasonable authorization dialog: the person has to read and tap. */
    private const val PROBE_TIMEOUT_MS = 30_000L
    private const val MAX_DETAIL = 200

    /** A root manager is installed. Says nothing about whether it is authorized. */
    fun detected(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * Asks the root manager for uid 0. Blocks until the person answers, the process exits,
     * or [PROBE_TIMEOUT_MS] passes; callers must not run it on the main thread.
     */
    fun probe(): Probe {
        if (!detected()) return Probe(State.UNAVAILABLE)
        val process = try {
            ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        } catch (error: Exception) {
            return Probe(State.DENIED, error.message?.take(MAX_DETAIL))
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { reader -> output.append(reader.readText()) }
            } catch (_: Exception) {
                // A destroyed process reports its own failure through the exit value below.
            }
        }.apply { isDaemon = true; start() }
        val finished = try {
            process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            return Probe(State.TIMEOUT)
        }
        reader.join(500)
        val text = output.toString().trim()
        return if (process.exitValue() == 0 && text.lineSequence().any { it.trim() == "0" }) {
            Probe(State.AUTHORIZED, text.take(MAX_DETAIL))
        } else {
            Probe(State.DENIED, text.take(MAX_DETAIL).ifEmpty { null })
        }
    }
}
