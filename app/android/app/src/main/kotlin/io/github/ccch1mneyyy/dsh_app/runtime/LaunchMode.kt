package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * How the next start reaches the guest.
 *
 * [AUTO] keeps the historical behaviour exactly: Direct when its saved switch asks for
 * it, PRoot otherwise. The explicit modes exist for phones where the person knows what
 * they want — Direct dies under Android 16's syscall filter, PRoot always works, and
 * root is the only way to get native hard links and a writable overlay on such a phone.
 */
enum class LaunchMode(val wire: String) {
    AUTO("auto"),

    /** PRoot only; never tries the direct loader. */
    COMPAT("compat"),

    /** Direct loader only; falls back to PRoot when the loader dies. */
    DIRECT("direct"),

    /** `su` + chroot + overlay; falls back to PRoot when root is missing or refused. */
    ROOT("root"),

    /**
     * Shizuku's shell (uid 2000) has no mount or chroot capability, so it can never start
     * the runtime. Kept as a mode so the choice is recorded and answered — the start
     * reports `shizukuCannotMount` and uses the compatible path — instead of silently
     * pretending the choice was honoured.
     */
    SHIZUKU("shizuku");

    companion object {
        fun parse(value: String?): LaunchMode = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}
