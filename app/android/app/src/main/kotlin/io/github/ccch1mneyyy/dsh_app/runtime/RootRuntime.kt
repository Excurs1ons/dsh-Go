package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The root backend's own system, kept apart from the PRoot one.
 *
 * Root mode changes what the guest sees: files are owned by uid 0 and the overlay's
 * writable layer holds everything new. Sharing one rootfs would make a later PRoot
 * start unable to write, or hide everything root mode wrote (the overlay's upper is
 * invisible to PRoot). So the two never share a tree.
 *
 * Created lazily — a phone that never turns root mode on keeps exactly the single
 * `rootfs` directory it always had. [state] is READY only for the runtime version the
 * copy was taken from; an engine upgrade makes it STALE rather than silently running a
 * mixed system.
 */
object RootRuntime {
    enum class State(val wire: String) { MISSING("missing"), COPYING("copying"), READY("ready"), STALE("stale") }

    /** Set while a root launch runs, so [files.GuestFiles] resolves against the same tree. */
    @Volatile private var active: File? = null
    @Volatile private var copying = false

    fun path(context: Context): File = File(context.filesDir, "runtime/rootfs-root")

    /** PRoot's tree; the only one that exists until root mode is turned on. */
    fun compat(context: Context): File = File(context.filesDir, "runtime/rootfs")

    /** The tree the running guest sees: the root copy while root mode runs, PRoot's otherwise. */
    fun active(context: Context): File = active ?: compat(context)

    fun activate(context: Context, root: File?) {
        active = if (root != null && root.isDirectory) root else null
    }

    private fun versionFile(context: Context) = File(context.filesDir, "runtime/rootfs-root.version")

    private fun staging(context: Context) = File(context.filesDir, "runtime/rootfs-root.staging")

    fun state(context: Context, runtimeVersion: String?): State {
        if (copying) return State.COPYING
        val root = path(context)
        if (!root.isDirectory) return State.MISSING
        val recorded = versionFile(context).takeIf { it.isFile }?.readText()?.trim()
        return if (recorded != null && recorded == runtimeVersion) State.READY else State.STALE
    }

    fun delete(context: Context) {
        active = null
        versionFile(context).delete()
        path(context).deleteRecursivelySafely()
    }

    /**
     * Copies [source] into the root tree, preserving symlinks as symlinks: PRoot stores
     * hard links that way (`/root/.l2s/…` targets), and following them would duplicate
     * the payload of every such file.
     *
     * Writes into a staging directory and renames it in, so a killed copy never leaves a
     * half system that looks READY. [onProgress] gets (copiedEntries, totalEntries).
     */
    @Synchronized
    fun create(context: Context, source: File, runtimeVersion: String, onProgress: (Int, Int) -> Unit) {
        check(!copying) { "a root system copy is already running" }
        val target = staging(context)
        target.deleteRecursivelySafely()
        copying = true
        try {
            target.mkdirs()
            val base = source.toPath()
            val total = countEntries(base)
            var copied = 0
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(target.toPath().resolve(base.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val destination = target.toPath().resolve(base.relativize(file).toString())
                    if (attrs.isSymbolicLink) {
                        Files.createSymbolicLink(destination, Files.readSymbolicLink(file))
                    } else {
                        Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    copied++
                    if (copied % PROGRESS_EVERY == 0) onProgress(copied, total)
                    return FileVisitResult.CONTINUE
                }
            })
            onProgress(copied, total)
            val root = path(context)
            root.deleteRecursivelySafely()
            check(target.renameTo(root)) { "cannot move the copied system into place" }
            versionFile(context).writeText(runtimeVersion)
        } catch (error: Exception) {
            target.deleteRecursivelySafely()
            throw if (error is IOException) error else IOException(error.message, error)
        } finally {
            copying = false
        }
    }

    private fun countEntries(base: Path): Int = try {
        var count = 0
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isSymbolicLink) count++
                return FileVisitResult.CONTINUE
            }
        })
        count
    } catch (_: Exception) {
        0
    }

    private const val PROGRESS_EVERY = 500
}
