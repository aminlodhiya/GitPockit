package dev.gitpockit.core.git.jgit

import dev.gitpockit.core.git.LineSelection
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream

/**
 * Line-level staging: builds a version of a file that contains only some of the changes
 * between [old] and [new].
 *
 * The diff is computed with the same algorithm as the diff screen (histogram), so line numbers
 * the user picked there match. For each changed block, removed lines stay unless selected, and
 * added lines are included only when selected. Unchanged lines are copied as they are.
 */
internal object PartialStaging {

    fun apply(old: ByteArray, new: ByteArray, selection: LineSelection): ByteArray {
        val a = RawText(old)
        val b = RawText(new)
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, a, b)
        val oldLines = Lines(old)
        val newLines = Lines(new)
        val out = Output()
        var oldPos = 0 // 0-based index into old
        for (edit in edits) {
            while (oldPos < edit.beginA) out.line(oldLines, oldPos++)
            for (i in edit.beginA until edit.endA) {
                if ((i + 1) !in selection.oldLines) out.line(oldLines, i) // keep unless removal selected
            }
            for (j in edit.beginB until edit.endB) {
                if ((j + 1) in selection.newLines) out.line(newLines, j) // add only if selected
            }
            oldPos = edit.endA
        }
        while (oldPos < a.size()) out.line(oldLines, oldPos++)
        return out.bytes()
    }

    /** Raw lines split on '\n' (a '\r' of CRLF stays part of the line), like RawText counts them. */
    private class Lines(private val bytes: ByteArray) {
        private val starts = buildList {
            if (bytes.isNotEmpty()) add(0)
            for (i in bytes.indices) if (bytes[i] == '\n'.code.toByte() && i + 1 < bytes.size) add(i + 1)
        }
        val size: Int get() = starts.size
        val missingFinalNewline: Boolean = bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()

        fun line(i: Int): ByteArray {
            val start = starts[i]
            var end = if (i + 1 < starts.size) starts[i + 1] - 1 else bytes.size
            if (i + 1 == starts.size && !missingFinalNewline) end = bytes.size - 1
            return bytes.copyOfRange(start, end)
        }
    }

    /** Everything that's a change in old→new, i.e. selecting all lines reproduces [new]. */
    fun everything(old: ByteArray, new: ByteArray): LineSelection {
        val a = RawText(old)
        val b = RawText(new)
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, a, b)
        val oldLines = HashSet<Int>()
        val newLines = HashSet<Int>()
        for (e in edits) {
            for (i in e.beginA until e.endA) oldLines += i + 1
            for (j in e.beginB until e.endB) newLines += j + 1
        }
        return LineSelection(oldLines, newLines)
    }

    /** Writes lines back with their original line endings (incl. a missing final newline). */
    private class Output {
        private val buf = ByteArrayOutputStream()
        private var pendingNewline = false

        fun line(source: Lines, index: Int) {
            if (pendingNewline) buf.write('\n'.code)
            buf.write(source.line(index))
            // Written lazily, so a last line that had no newline in its source stays without one.
            pendingNewline = !(index == source.size - 1 && source.missingFinalNewline)
        }

        fun bytes(): ByteArray {
            if (pendingNewline) buf.write('\n'.code)
            return buf.toByteArray()
        }
    }
}
