package dev.gitpockit.core.git

/**
 * Lays out a commit graph ("git log --graph") for rows listed newest first in topological order.
 *
 * Each commit gets a lane (column). Lanes hold the id of the commit expected next in that column;
 * a finished lane is freed and reused. Each row lists the line segments to draw in its upper half
 * (from the lanes above into this row) and lower half (from this row towards the parents below).
 */
object CommitGraph {

    /** A line inside one row: from lane [from] to lane [to], in the top or bottom half of the row. */
    data class Segment(val from: Int, val to: Int, val top: Boolean, val color: Int)

    data class Row(
        val commitId: String,
        val lane: Int,
        /** Colour index of this commit's lane. */
        val color: Int,
        val segments: List<Segment>,
        /** Number of lanes in use around this row (for its drawing width). */
        val width: Int,
    )

    fun layout(commits: List<CommitInfo>): List<Row> {
        val lanes = ArrayList<String?>() // expected commit id per lane
        val laneColor = ArrayList<Int>()
        var nextColor = 0
        val rows = ArrayList<Row>(commits.size)

        fun freeLane(): Int {
            val i = lanes.indexOf(null)
            if (i >= 0) return i
            lanes += null
            laneColor += 0
            return lanes.size - 1
        }

        for (c in commits) {
            val segments = ArrayList<Segment>()
            // Which lane is this commit in? A new branch tip gets a free lane and a new colour.
            var lane = lanes.indexOf(c.id)
            if (lane < 0) {
                lane = freeLane()
                laneColor[lane] = nextColor++
            }
            val color = laneColor[lane]

            // Top half: every occupied lane continues down; lanes waiting for this commit merge into it.
            for (i in lanes.indices) {
                val expected = lanes[i] ?: continue
                if (expected == c.id) {
                    segments += Segment(i, lane, top = true, color = laneColor[i])
                    if (i != lane) lanes[i] = null // a second lane that pointed here ends
                } else {
                    segments += Segment(i, i, top = true, color = laneColor[i])
                }
            }

            // This commit's lane now waits for its first parent (or ends for a root commit).
            lanes[lane] = c.parentIds.firstOrNull()
            // Further parents (merge commits) join an existing lane or open a new one.
            val opened = HashSet<Int>()
            for (p in c.parentIds.drop(1)) {
                var target = lanes.indexOf(p)
                if (target < 0) {
                    target = freeLane()
                    lanes[target] = p
                    laneColor[target] = nextColor++
                    opened += target
                }
                segments += Segment(lane, target, top = false, color = laneColor[target])
            }

            // Bottom half: lanes still waiting for a commit continue down.
            for (i in lanes.indices) {
                if (lanes[i] == null) continue
                if (i == lane && c.parentIds.isNotEmpty()) segments += Segment(lane, lane, top = false, color = color)
                else if (i != lane && i !in opened) segments += Segment(i, i, top = false, color = laneColor[i])
            }

            // Trim trailing free lanes so the graph doesn't grow wider than needed.
            while (lanes.isNotEmpty() && lanes.last() == null && lanes.size - 1 > lane) {
                lanes.removeAt(lanes.size - 1)
                laneColor.removeAt(laneColor.size - 1)
            }
            val width = maxOf(lanes.size, lane + 1, (segments.maxOfOrNull { maxOf(it.from, it.to) } ?: 0) + 1)
            rows += Row(c.id, lane, color, segments, width)
        }
        return rows
    }
}
