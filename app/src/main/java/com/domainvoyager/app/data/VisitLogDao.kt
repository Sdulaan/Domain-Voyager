package com.domainvoyager.app.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface VisitLogDao {
    @Query("SELECT * FROM visit_logs ORDER BY visitTime DESC")
    fun getAllLogs(): LiveData<List<VisitLog>>

    @Query("SELECT * FROM visit_logs ORDER BY visitTime DESC LIMIT 100")
    fun getRecentLogs(): LiveData<List<VisitLog>>

    @Insert
    suspend fun insertLog(log: VisitLog): Long

    @Query("DELETE FROM visit_logs")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM visit_logs WHERE status = 'Success'")
    suspend fun getSuccessCount(): Int

    @Query("SELECT COUNT(*) FROM visit_logs WHERE status = 'Failed'")
    suspend fun getFailedCount(): Int
}
