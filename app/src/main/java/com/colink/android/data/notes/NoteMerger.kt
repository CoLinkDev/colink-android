package com.colink.android.data.notes

/**
 * Line-based three-way merge for note content, mirroring the protocol rules:
 * an unchanged side loses against a changed side, identical edits collapse,
 * and overlapping changes from both sides are a conflict that must be
 * resolved by the user.
 */
object NoteMerger {
    data class FieldMerge(val resolved: String?, val local: String, val cloud: String) {
        val isResolved: Boolean get() = resolved != null
    }

    data class NoteMerge(
        val title: FieldMerge,
        val markdown: FieldMerge,
        val mergedTagIds: List<String>,
        val mergedAttachmentIds: List<String>,
    )

    fun mergeField(ancestor: String, local: String, cloud: String): FieldMerge =
        when {
            local == cloud -> FieldMerge(local, local, cloud)
            local == ancestor -> FieldMerge(cloud, local, cloud)
            cloud == ancestor -> FieldMerge(local, local, cloud)
            else -> FieldMerge(null, local, cloud)
        }

    /**
     * Set three-way merge: additions from either side are kept, removals are
     * honored unless the other side re-added the entry.
     */
    fun mergeSet(ancestor: List<String>, local: List<String>, cloud: List<String>): List<String> {
        val resolved = LinkedHashSet<String>()
        for (id in ancestor + local + cloud) {
            val inAncestor = ancestor.contains(id)
            val inLocal = local.contains(id)
            val inCloud = cloud.contains(id)
            val keep = when {
                inLocal && inCloud -> true
                inLocal -> !inAncestor
                inCloud -> !inAncestor
                else -> false
            }
            if (keep) resolved.add(id)
        }
        return resolved.sorted()
    }

    fun mergeMarkdown(ancestor: String, local: String, cloud: String): String? {
        if (local == cloud) return local
        if (local == ancestor) return cloud
        if (cloud == ancestor) return local

        val ancestorLines = ancestor.splitLines()
        val localHunks = diffHunks(ancestor, local)
        val cloudHunks = diffHunks(ancestor, cloud)

        if (localHunks == null || cloudHunks == null) {
            return null
        }

        data class Hunk(val start: Int, val end: Int, val replacement: List<String>)

        val output = mutableListOf<String>()
        var position = 0
        var localIndex = 0
        var cloudIndex = 0

        fun apply(hunk: Triple<Int, Int, List<String>>) {
            while (position < hunk.first && position < ancestorLines.size) {
                output.add(ancestorLines[position])
                position++
            }
            output.addAll(hunk.third)
            position = maxOf(position, hunk.second)
        }

        while (localIndex < localHunks.size || cloudIndex < cloudHunks.size) {
            val localHunk = localHunks.getOrNull(localIndex)
            val cloudHunk = cloudHunks.getOrNull(cloudIndex)

            if (localHunk != null && cloudHunk != null &&
                localHunk.first == cloudHunk.first &&
                localHunk.second == cloudHunk.second &&
                localHunk.third == cloudHunk.third
            ) {
                apply(localHunk)
                localIndex++
                cloudIndex++
                continue
            }

            if (localHunk != null && cloudHunk != null && hunksOverlap(localHunk, cloudHunk)) {
                return null
            }

            val takeLocal = when {
                localHunk == null -> false
                cloudHunk == null -> true
                else -> localHunk.first <= cloudHunk.first
            }

            val hunk = if (takeLocal) localHunk!! else cloudHunk!!
            val other = if (takeLocal) cloudHunk else localHunk
            if (other != null && other.first < hunk.second) {
                // Strict overlap between the two sides.
                return null
            }

            apply(hunk)
            if (takeLocal) {
                localIndex++
            } else {
                cloudIndex++
            }
        }

        while (position < ancestorLines.size) {
            output.add(ancestorLines[position])
            position++
        }

        val merged = output.joinToString("\n")
        val endsWithNewline = local.endsWith("\n") || cloud.endsWith("\n")
        return if (endsWithNewline && merged.isNotEmpty() && !merged.endsWith("\n")) "$merged\n" else merged
    }

    fun mergeNote(
        ancestorTitle: String,
        ancestorMarkdown: String,
        ancestorTagIds: List<String>,
        ancestorAttachmentIds: List<String>,
        localTitle: String,
        localMarkdown: String,
        localTagIds: List<String>,
        localAttachmentIds: List<String>,
        cloudTitle: String,
        cloudMarkdown: String,
        cloudTagIds: List<String>,
        cloudAttachmentIds: List<String>,
    ): NoteMerge =
        NoteMerge(
            title = mergeField(ancestorTitle, localTitle, cloudTitle),
            markdown = FieldMerge(mergeMarkdown(ancestorMarkdown, localMarkdown, cloudMarkdown), localMarkdown, cloudMarkdown),
            mergedTagIds = mergeSet(ancestorTagIds, localTagIds, cloudTagIds),
            mergedAttachmentIds = mergeSet(ancestorAttachmentIds, localAttachmentIds, cloudAttachmentIds),
        )

    /** Returns hunks of (start, end, replacement) against the ancestor, or
     * null when the text is too large for a reliable automatic merge. */
    private fun diffHunks(ancestor: String, current: String): List<Triple<Int, Int, List<String>>>? {
        val a = ancestor.splitLines()
        val b = current.splitLines()
        if (a.size > MAX_MERGE_LINES || b.size > MAX_MERGE_LINES) {
            return null
        }

        val lcs = lcsLengths(a, b) ?: return null

        val ops = mutableListOf<EditOp>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    ops.add(EditOp.Keep(a[i]))
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    ops.add(EditOp.Delete(a[i]))
                    i++
                }
                else -> {
                    ops.add(EditOp.Insert(b[j]))
                    j++
                }
            }
        }
        while (i < a.size) {
            ops.add(EditOp.Delete(a[i]))
            i++
        }
        while (j < b.size) {
            ops.add(EditOp.Insert(b[j]))
            j++
        }

        val hunks = mutableListOf<Triple<Int, Int, List<String>>>()
        var ancestorIndex = 0
        var runStart = -1
        var runEnd = -1
        val runReplacement = mutableListOf<String>()

        fun flushRun() {
            if (runStart >= 0) {
                hunks.add(Triple(runStart, runEnd, runReplacement.toList()))
                runStart = -1
                runEnd = -1
                runReplacement.clear()
            }
        }

        for (op in ops) {
            when (op) {
                is EditOp.Keep -> {
                    flushRun()
                    ancestorIndex++
                }
                is EditOp.Delete -> {
                    if (runStart < 0) runStart = ancestorIndex
                    runEnd = ancestorIndex + 1
                    ancestorIndex++
                }
                is EditOp.Insert -> {
                    if (runStart < 0) {
                        runStart = ancestorIndex
                        runEnd = ancestorIndex
                    }
                    runReplacement.add(op.line)
                }
            }
        }
        flushRun()

        return hunks
    }

    private sealed interface EditOp {
        data class Keep(val line: String) : EditOp
        data class Delete(val line: String) : EditOp
        data class Insert(val line: String) : EditOp
    }

    private fun hunksOverlap(
        left: Triple<Int, Int, List<String>>,
        right: Triple<Int, Int, List<String>>,
    ): Boolean = when {
        left.first == left.second && right.first == right.second -> left.first == right.first
        left.first == left.second -> left.first >= right.first && left.first < right.second
        right.first == right.second -> right.first >= left.first && right.first < left.second
        else -> left.first < right.second && right.first < left.second
    }

    private fun lcsLengths(a: List<String>, b: List<String>): Array<IntArray>? {
        if (a.size > MAX_MERGE_LINES || b.size > MAX_MERGE_LINES) {
            return null
        }
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        return table
    }

    private const val MAX_MERGE_LINES = 4000

    private fun String.splitLines(): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        for (index in this.indices) {
            if (this[index] == '\n') {
                val end = if (index > start && this[index - 1] == '\r') index - 1 else index
                lines.add(substring(start, end))
                start = index + 1
            }
        }
        if (start < length) {
            lines.add(substring(start))
        }
        return lines
    }
}
