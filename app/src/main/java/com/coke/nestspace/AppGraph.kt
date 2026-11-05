package com.coke.nestspace

import android.content.Context
import com.coke.nestspace.data.AppRepository
import com.coke.nestspace.engine.ContainerEngine
import com.coke.nestspace.engine.MiniContainerEngine
import com.coke.nestspace.tools.ToolRegistry

/**
 * Shared graph so the local agent (UI process) and the MCP server (foreground
 * service) use the same ToolRegistry, LeaseManager and container engine.
 */
object AppGraph {
    @Volatile
    private var registry: ToolRegistry? = null

    @Volatile
    private var containerEngine: ContainerEngine? = null

    fun engine(context: Context): ContainerEngine =
        containerEngine ?: synchronized(this) {
            containerEngine ?: MiniContainerEngine(context.applicationContext)
                .also { containerEngine = it }
        }

    fun registry(context: Context): ToolRegistry =
        registry ?: synchronized(this) {
            registry ?: ToolRegistry(
                context.applicationContext,
                AppRepository(context.applicationContext),
                engine(context)
            ).also { registry = it }
        }
}
