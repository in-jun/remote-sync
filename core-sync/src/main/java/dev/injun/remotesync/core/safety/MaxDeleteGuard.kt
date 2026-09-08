package dev.injun.remotesync.core.safety

import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.model.SyncPlan

/**
 * Refuses a plan that would delete an alarming fraction of a replica — the guard
 * against catastrophic mass-deletion (e.g. an SMB share that returns empty because
 * it failed to mount). Modeled on rclone-bisync's --max-delete.
 *
 * @property minDeletionsToTrigger only enforced past this many deletions, so it never
 *   nuisance-aborts on tiny working sets.
 */
class MaxDeleteGuard(
    val threshold: Double = 0.5,
    val minDeletionsToTrigger: Int = 5,
) {
    init {
        require(threshold in 0.0..1.0) { "threshold must be in 0.0..1.0, was $threshold" }
        require(minDeletionsToTrigger >= 0) { "minDeletionsToTrigger must be >= 0" }
    }

    fun check(plan: SyncPlan, local: Snapshot, remote: Snapshot): SafetyVerdict {
        evaluate(Side.LOCAL, plan.localDeletionCount, local.paths.size)?.let { return it }
        evaluate(Side.REMOTE, plan.remoteDeletionCount, remote.paths.size)?.let { return it }
        return SafetyVerdict.Ok
    }

    private fun evaluate(side: Side, deletions: Int, total: Int): SafetyVerdict.Abort? {
        if (deletions < minDeletionsToTrigger || total == 0) return null
        val fraction = deletions.toDouble() / total.toDouble()
        if (fraction <= threshold) return null
        return SafetyVerdict.Abort(side, deletions, total, fraction, threshold)
    }

    enum class Side { LOCAL, REMOTE }
}

sealed interface SafetyVerdict {
    data object Ok : SafetyVerdict

    data class Abort(
        val side: MaxDeleteGuard.Side,
        val deletions: Int,
        val total: Int,
        val fraction: Double,
        val threshold: Double,
    ) : SafetyVerdict {
        val message: String
            get() = "Aborting: %d of %d files (%.0f%%) would be deleted on %s, exceeding the %.0f%% safety limit."
                .format(deletions, total, fraction * 100, side.name.lowercase(), threshold * 100)
    }
}
