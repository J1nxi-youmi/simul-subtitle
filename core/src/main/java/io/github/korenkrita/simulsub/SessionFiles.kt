package io.github.korenkrita.simulsub

import java.io.File

/**
 * Pairs a session log with the recording of the same session: `session-….jsonl[.part]` ↔ `session-….wav[.part]`
 * in the same directory (the shared name is [SessionLog.stem]; AudioRecorder renames its `.part` on a successful
 * close, exactly like the log does). Deleting, sharing and sizing a record are done as a pair, so removing a log
 * never leaves the recording behind as an orphan. Only file pairing lives here; size formatting (KB/MB text) is
 * the UI's.
 */
object SessionFiles {
    private const val PART = ".part"

    /** The stem shared by one session's files; null when [log] is not named like a session log. */
    private fun stemOf(log: File): String? = log.name.let { n ->
        when {
            n.endsWith(".jsonl$PART") -> n.removeSuffix(".jsonl$PART")
            n.endsWith(".jsonl") -> n.removeSuffix(".jsonl")
            else -> null
        }
    }

    /** The complete and the in-progress recording of [log]'s session, existing or not (normally at most one does). */
    private fun audioFiles(log: File): List<File> {
        val stem = stemOf(log) ?: return emptyList()
        val dir = log.parentFile ?: return emptyList()
        return listOf(File(dir, "$stem.wav"), File(dir, "$stem.wav$PART"))
    }

    /** The audio belonging to session log [log]: the complete `.wav` first, the in-progress `.wav.part` second; null when there is neither. */
    fun audioOf(log: File): File? = audioFiles(log).firstOrNull { it.exists() }

    /**
     * Deletes session log [log] together with its recording. A log a session may still write ([SessionLog.isActive])
     * is refused — and its recording stays with it; a recording that cannot be deleted keeps its log, so a failed
     * delete never splits the pair. Deleting a log that is already gone still sweeps an orphaned recording.
     * @return true if the log is gone afterwards
     */
    fun deleteLog(log: File): Boolean {
        if (SessionLog.isActive(log)) return false
        if (audioFiles(log).any { it.exists() && !it.delete() }) return false
        return SessionLog.delete(log)
    }

    /**
     * Deletes every record (log and recording) no session may still write.
     * @return how many records are left (running or undeletable)
     */
    fun deleteAll(root: File): Int = SessionLog.list(root).count { !deleteLog(it) }

    /** Total size of every session log and recording (complete and `.part`) in [SessionLog.dir] below [root]. */
    fun totalBytes(root: File): Long = SessionLog.dir(root).listFiles { f ->
        f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl$PART") ||
            f.name.endsWith(".wav") || f.name.endsWith(".wav$PART"))
    }?.sumOf { it.length() } ?: 0L
}
