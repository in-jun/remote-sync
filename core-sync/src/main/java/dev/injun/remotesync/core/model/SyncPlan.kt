package dev.injun.remotesync.core.model

/** Pure data: the per-path decisions for one reconciliation pass. */
data class SyncPlan(val actions: List<SyncAction>) {

    val conflicts: List<Conflict> get() = actions.filterIsInstance<Conflict>()
    val localDeletionCount: Int get() = actions.count { it is DeleteLocal }
    val remoteDeletionCount: Int get() = actions.count { it is DeleteRemote }

    fun action(path: String): SyncAction? = actions.firstOrNull { it.path == path }
}
