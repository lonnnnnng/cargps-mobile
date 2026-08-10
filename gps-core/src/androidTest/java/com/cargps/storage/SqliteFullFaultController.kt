package com.cargps.storage

import android.database.sqlite.SQLiteFullException
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 作者：long
 *
 * 通过限制测试数据库自身的最大页数制造真实 SQLITE_FULL，不消耗共享模拟器的剩余磁盘空间。
 * 填充数据只写入活动轨迹表的负时间戳行，解除故障时会删除并恢复自增序列。
 */
internal object SqliteFullFaultController {
    fun arm(database: SupportSQLiteDatabase): SqliteFullFaultState {
        val originalMaxPageCount = database.pragmaLong("max_page_count")
        val pageCount = database.pragmaLong("page_count")
        val pageSize = database.pragmaLong("page_size")
        val limitedMaxPageCount = pageCount + EXTRA_PAGE_COUNT
        val appliedMaxPageCount = database.setMaxPageCount(limitedMaxPageCount)
        check(appliedMaxPageCount == limitedMaxPageCount) {
            "无法限制 SQLite 测试数据库页数：expected=$limitedMaxPageCount actual=$appliedMaxPageCount"
        }

        var fillerIndex = 0
        var largeRowFailure: SQLiteFullException? = null
        while (largeRowFailure == null && fillerIndex < MAX_FILLER_ATTEMPTS) {
            try {
                database.execSQL(
                    "INSERT INTO active_point(timestamp, speed, distance, moving) " +
                        "VALUES (?, 0.0, zeroblob(?), 0)",
                    arrayOf<Any>(
                        fillerTimestamp(fillerIndex),
                        pageSize.toInt() * LARGE_ROW_PAGE_MULTIPLIER,
                    ),
                )
                fillerIndex += 1
            } catch (error: SQLiteFullException) {
                largeRowFailure = error
            }
        }
        check(largeRowFailure != null) {
            "限制页数后仍未触发 SQLITE_FULL，已尝试 $fillerIndex 条大填充行"
        }

        var compactRowFailure: SQLiteFullException? = null
        while (compactRowFailure == null && fillerIndex < MAX_FILLER_ATTEMPTS) {
            try {
                // 作者：long｜大 BLOB 先耗尽可分配页，小行再填满活动轨迹叶页，确保下一批正常轨迹也需要新页。
                database.execSQL(
                    "INSERT INTO active_point(timestamp, speed, distance, moving) VALUES (?, 0.0, 0.0, 0)",
                    arrayOf(fillerTimestamp(fillerIndex)),
                )
                fillerIndex += 1
            } catch (error: SQLiteFullException) {
                compactRowFailure = error
            }
        }
        check(compactRowFailure != null) {
            "SQLITE_FULL 后活动轨迹叶页仍未填满，已尝试 $fillerIndex 条填充行"
        }

        return SqliteFullFaultState(
            originalMaxPageCount = originalMaxPageCount,
            fillerRowCount = fillerIndex,
        )
    }

    fun release(database: SupportSQLiteDatabase, state: SqliteFullFaultState) {
        val restoredMaxPageCount = database.setMaxPageCount(state.originalMaxPageCount)
        check(restoredMaxPageCount == state.originalMaxPageCount) {
            "无法恢复 SQLite 最大页数：expected=${state.originalMaxPageCount} actual=$restoredMaxPageCount"
        }
        database.execSQL("DELETE FROM active_point WHERE timestamp < 0")
        // 作者：long｜移除填充行后把 AUTOINCREMENT 恢复到真实轨迹最大序列，避免测试故障污染检查点边界。
        database.execSQL(
            "UPDATE sqlite_sequence SET seq = " +
                "COALESCE((SELECT MAX(sequence) FROM active_point), 0) WHERE name = 'active_point'",
        )
    }

    private fun SupportSQLiteDatabase.pragmaLong(name: String): Long =
        query("PRAGMA $name").use { cursor ->
            check(cursor.moveToFirst()) { "无法读取 PRAGMA $name" }
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.setMaxPageCount(value: Long): Long =
        query("PRAGMA max_page_count = $value").use { cursor ->
            check(cursor.moveToFirst()) { "无法设置 PRAGMA max_page_count" }
            cursor.getLong(0)
        }

    private fun fillerTimestamp(index: Int): Long = FILLER_TIMESTAMP_BASE - index

    private const val EXTRA_PAGE_COUNT = 8L
    private const val LARGE_ROW_PAGE_MULTIPLIER = 2
    private const val MAX_FILLER_ATTEMPTS = 4_096
    private const val FILLER_TIMESTAMP_BASE = -1_000_000L
}

internal data class SqliteFullFaultState(
    val originalMaxPageCount: Long,
    val fillerRowCount: Int,
)
