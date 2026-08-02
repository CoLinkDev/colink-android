package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.colink.android.data.local.db.entity.DiagnosticLogEntity

@Dao
interface DiagnosticLogDao {
    @Insert
    suspend fun insert(entry: DiagnosticLogEntity)

    @Query(
        """
        DELETE FROM diagnostic_logs
        WHERE id NOT IN (
            SELECT id FROM diagnostic_logs ORDER BY id DESC LIMIT :maxEntries
        )
        """,
    )
    suspend fun trimTo(maxEntries: Int)

    @Query(
        """
        SELECT * FROM diagnostic_logs
        WHERE createdAt >= :fromMillis AND createdAt <= :toMillis
        ORDER BY createdAt ASC, id ASC
        """,
    )
    suspend fun loadBetween(fromMillis: Long, toMillis: Long): List<DiagnosticLogEntity>
}
