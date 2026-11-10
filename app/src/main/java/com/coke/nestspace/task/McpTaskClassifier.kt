package com.coke.nestspace.task

/**
 * MCP tasks share the operation task registry with local assistant work. Their
 * roots use the stable `mcp:` owner prefix, while tool/download children may
 * retain the local assistant owner. Resolve the parent chain before deciding
 * whether a task belongs in the MCP task center.
 */
internal object McpTaskClassifier {
    fun isMcpTask(
        task: OperationTaskRegistry.Snapshot,
        tasks: List<OperationTaskRegistry.Snapshot>
    ): Boolean = rootOf(task, tasks.associateBy { it.id })
        .ownerId
        .startsWith("mcp:")

    fun roots(tasks: List<OperationTaskRegistry.Snapshot>): List<OperationTaskRegistry.Snapshot> {
        val byId = tasks.associateBy { it.id }
        return tasks
            .filter { task ->
                isMcpTask(task, tasks) && rootOf(task, byId).id == task.id
            }
            .sortedByDescending { it.createdAt }
    }

    fun children(
        root: OperationTaskRegistry.Snapshot,
        tasks: List<OperationTaskRegistry.Snapshot>
    ): List<OperationTaskRegistry.Snapshot> {
        val byId = tasks.associateBy { it.id }
        return tasks
            .filter { task ->
                task.id != root.id && rootOf(task, byId).id == root.id
            }
            .sortedBy { it.createdAt }
    }

    private fun rootOf(
        task: OperationTaskRegistry.Snapshot,
        byId: Map<String, OperationTaskRegistry.Snapshot>
    ): OperationTaskRegistry.Snapshot {
        var current = task
        val visited = HashSet<String>()
        while (visited.add(current.id)) {
            val parentId = current.parentTaskId ?: break
            current = byId[parentId] ?: break
        }
        return current
    }
}
