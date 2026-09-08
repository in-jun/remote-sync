package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.model.ConflictKind
import dev.injun.remotesync.core.safety.SafetyVerdict

sealed interface SyncResult {

    /**
     * The pass ran to completion. [failures] are actions that threw and were skipped
     * over so the rest of the plan still applied; [skippedPaths] are destructive
     * actions withheld because their target changed after the scan. Both are retried
     * on the next pass (their ancestors were not committed).
     */
    data class Success(
        val actionsApplied: Int,
        val conflicts: List<ConflictReport>,
        val failures: List<SyncFailure> = emptyList(),
        val skippedPaths: List<String> = emptyList(),
    ) : SyncResult {
        val hadConflicts: Boolean get() = conflicts.isNotEmpty()
    }

    data class Aborted(val verdict: SafetyVerdict.Abort) : SyncResult
}

/** One action that threw; the pass continued with the remaining actions. */
data class SyncFailure(val path: String, val reason: String)

/**
 * A preserved conflict. [conflictCopyPath] is null for modify-vs-delete, where the
 * modification is kept in place and there is no second file to preserve.
 */
data class ConflictReport(
    val path: String,
    val kind: ConflictKind,
    val conflictCopyPath: String?,
)
