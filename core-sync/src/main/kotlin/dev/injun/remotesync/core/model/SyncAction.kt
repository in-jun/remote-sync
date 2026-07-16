package dev.injun.remotesync.core.model

/**
 * One reconciliation decision for a path. [ancestorAfter] is the ancestor entry to
 * persist once the action is applied (null = remove it); this is what keeps the
 * ancestor as the single source of truth and the crash-recovery journal.
 */
sealed interface SyncAction {
    val path: String
    val ancestorAfter: FileMeta?
}

/** Copy the remote version down to local. */
data class Pull(override val path: String, val remote: FileMeta) : SyncAction {
    override val ancestorAfter: FileMeta get() = remote
}

/** Copy the local version up to remote. */
data class Push(override val path: String, val local: FileMeta) : SyncAction {
    override val ancestorAfter: FileMeta get() = local
}

data class DeleteLocal(override val path: String) : SyncAction {
    override val ancestorAfter: FileMeta? get() = null
}

data class DeleteRemote(override val path: String) : SyncAction {
    override val ancestorAfter: FileMeta? get() = null
}

/** Both sides already agree; no I/O, only record the agreed ancestor state. */
data class Converged(
    override val path: String,
    override val ancestorAfter: FileMeta?,
) : SyncAction

/**
 * Both sides diverged in a way a one-sided propagation would clobber. The executor
 * preserves BOTH versions and surfaces it; the ancestor is left unchanged until the
 * conflict is materialized/resolved.
 */
data class Conflict(
    override val path: String,
    val kind: ConflictKind,
    val local: FileMeta?,
    val remote: FileMeta?,
    private val ancestorBefore: FileMeta?,
) : SyncAction {
    override val ancestorAfter: FileMeta? get() = ancestorBefore
}

enum class ConflictKind {
    CREATE_CREATE,
    MODIFY_MODIFY,
    MODIFY_DELETE,
    DELETE_MODIFY,

    /**
     * The path collides with a sibling that differs only by case or Unicode
     * normalization, which a case-insensitive/normalizing replica stores as ONE file.
     * Any propagation could silently overwrite the sibling, so the executor performs
     * no I/O; the user resolves it by renaming one of the files.
     */
    PATH_COLLISION,
}
