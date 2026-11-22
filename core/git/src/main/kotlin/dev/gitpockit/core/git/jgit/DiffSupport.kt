package dev.gitpockit.core.git.jgit

import dev.gitpockit.core.git.ChangeType
import dev.gitpockit.core.git.DiffHunk
import dev.gitpockit.core.git.DiffLine
import dev.gitpockit.core.git.DiffLineType
import dev.gitpockit.core.git.FileDiff
import org.eclipse.jgit.diff.DiffEntry

internal val DiffEntry.displayPath: String
    get() = if (changeType == DiffEntry.ChangeType.DELETE) oldPath else newPath

internal val DiffEntry.ChangeType.isMove: Boolean
    get() = this == DiffEntry.ChangeType.RENAME || this == DiffEntry.ChangeType.COPY

internal fun DiffEntry.ChangeType.toChangeType(): ChangeType = when (this) {
    DiffEntry.ChangeType.ADD -> ChangeType.ADDED
    DiffEntry.ChangeType.MODIFY -> ChangeType.MODIFIED
    DiffEntry.ChangeType.DELETE -> ChangeType.DELETED
    DiffEntry.ChangeType.RENAME -> ChangeType.RENAMED
    DiffEntry.ChangeType.COPY -> ChangeType.COPIED
}

/** Parses the unified diff text produced by JGit's DiffFormatter for a single file. */
internal object UnifiedDiffParser {
    private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

    fun parse(text: String, path: String, oldPath: String?, type: ChangeType): FileDiff {
        val hunks = mutableListOf<DiffHunk>()
        var lines: MutableList<DiffLine>? = null
        var header = ""
        var oldNo = 0
        var newNo = 0
        var binary = false

        fun flush() {
            lines?.let { hunks += DiffHunk(header, it) }
            lines = null
        }

        for (raw in text.lineSequence()) {
            val match = HUNK.matchEntire(raw)
            when {
                match != null -> {
                    flush()
                    header = raw
                    oldNo = match.groupValues[1].toInt()
                    newNo = match.groupValues[2].toInt()
                    lines = mutableListOf()
                }
                lines == null -> if (raw.startsWith("Binary files")) binary = true
                raw.startsWith("+") -> lines!! += DiffLine(DiffLineType.ADDED, raw.substring(1), null, newNo++)
                raw.startsWith("-") -> lines!! += DiffLine(DiffLineType.REMOVED, raw.substring(1), oldNo++, null)
                raw.startsWith(" ") -> lines!! += DiffLine(DiffLineType.CONTEXT, raw.substring(1), oldNo++, newNo++)
                // "\ No newline at end of file" and the trailing empty line carry no content.
                else -> Unit
            }
        }
        flush()
        return FileDiff(path = path, oldPath = oldPath, type = type, isBinary = binary, hunks = hunks)
    }
}
