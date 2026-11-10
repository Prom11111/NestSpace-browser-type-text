package com.coke.nestspace.task

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable task receipts. High-frequency progress stays in memory; lifecycle and
 * terminal results are persisted so a process restart can report interruption.
 */
internal class TaskJournal(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE operation_tasks (
                task_id TEXT PRIMARY KEY NOT NULL,
                request_id TEXT,
                request_hash TEXT,
                owner_id TEXT NOT NULL,
                parent_task_id TEXT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                replace_key TEXT,
                state TEXT NOT NULL,
                progress_percent INTEGER,
                progress_current INTEGER,
                progress_total INTEGER,
                bytes_per_second INTEGER,
                eta_seconds INTEGER,
                message TEXT NOT NULL,
                result TEXT,
                error TEXT,
                cleanup_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX operation_tasks_owner_request
            ON operation_tasks(owner_id, request_id)
            WHERE request_id IS NOT NULL
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) = Unit

    fun recoverAndLoad(): List<OperationTaskRegistry.Snapshot> {
        val interrupted = ContentValues().apply {
            put("state", OperationTaskRegistry.State.FAILED.name)
            put("message", "interrupted")
            put("error", "The app process stopped before this task finished.")
            put("finished_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "operation_tasks",
            interrupted,
            "finished_at=0 OR state IN (?,?,?,?,?,?,?)",
            arrayOf(
                OperationTaskRegistry.State.QUEUED.name,
                OperationTaskRegistry.State.PREPARING.name,
                OperationTaskRegistry.State.RUNNING.name,
                OperationTaskRegistry.State.WAITING_USER.name,
                OperationTaskRegistry.State.PAUSED.name,
                OperationTaskRegistry.State.CANCELLING.name,
                OperationTaskRegistry.State.FINALIZING.name
            )
        )
        return readableDatabase.query(
            "operation_tasks",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
            "200"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val state = runCatching {
                        OperationTaskRegistry.State.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("state"))
                        )
                    }.getOrDefault(OperationTaskRegistry.State.FAILED)
                    add(
                        OperationTaskRegistry.Snapshot(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
                            requestId = cursor.optionalString("request_id"),
                            requestHash = cursor.optionalString("request_hash"),
                            ownerId = cursor.getString(cursor.getColumnIndexOrThrow("owner_id")),
                            parentTaskId = cursor.optionalString("parent_task_id"),
                            kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            replaceKey = cursor.optionalString("replace_key"),
                            state = state,
                            progressPercent = cursor.optionalLong("progress_percent")?.toInt(),
                            progressCurrent = cursor.optionalLong("progress_current"),
                            progressTotal = cursor.optionalLong("progress_total"),
                            bytesPerSecond = cursor.optionalLong("bytes_per_second"),
                            etaSeconds = cursor.optionalLong("eta_seconds"),
                            message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
                            result = cursor.optionalString("result"),
                            error = cursor.optionalString("error"),
                            cleanupError = cursor.optionalString("cleanup_error"),
                            stateRevision = 0L,
                            progressRevision = 0L,
                            lastProgressAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                            lastHeartbeatAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                            finishedAt = cursor.getLong(cursor.getColumnIndexOrThrow("finished_at"))
                        )
                    )
                }
            }
        }
    }

    fun save(task: OperationTaskRegistry.Snapshot) {
        writableDatabase.insertWithOnConflict(
            "operation_tasks",
            null,
            task.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
        prune()
    }

    fun delete(taskId: String) {
        writableDatabase.delete("operation_tasks", "task_id=?", arrayOf(taskId))
    }

    private fun prune() {
        writableDatabase.execSQL(
            """
            DELETE FROM operation_tasks
            WHERE finished_at>0 AND task_id NOT IN (
                SELECT task_id FROM operation_tasks
                ORDER BY created_at DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf(MAX_TASKS)
        )
    }

    private fun OperationTaskRegistry.Snapshot.toValues() = ContentValues().apply {
        put("task_id", id)
        put("request_id", requestId)
        put("request_hash", requestHash)
        put("owner_id", ownerId)
        put("parent_task_id", parentTaskId)
        put("kind", kind)
        put("title", title)
        put("replace_key", replaceKey)
        put("state", state.name)
        put("progress_percent", progressPercent)
        put("progress_current", progressCurrent)
        put("progress_total", progressTotal)
        put("bytes_per_second", bytesPerSecond)
        put("eta_seconds", etaSeconds)
        put("message", message)
        put("result", result)
        put("error", error)
        put("cleanup_error", cleanupError)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("finished_at", finishedAt)
    }

    private fun android.database.Cursor.optionalString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.optionalLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private companion object {
        const val NAME = "operation-tasks.db"
        const val VERSION = 1
        const val MAX_TASKS = 200
    }
}
