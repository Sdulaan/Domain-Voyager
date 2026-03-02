package com.domainvoyager.app.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface DomainDao {
    @Query("SELECT * FROM domains ORDER BY id ASC")
    fun getAllDomains(): LiveData<List<Domain>>

    @Query("SELECT * FROM domains WHERE isActive = 1 ORDER BY id ASC")
    suspend fun getActiveDomains(): List<Domain>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDomain(domain: Domain): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDomains(domains: List<Domain>)

    @Update
    suspend fun updateDomain(domain: Domain)

    @Delete
    suspend fun deleteDomain(domain: Domain)

    @Query("DELETE FROM domains")
    suspend fun deleteAllDomains()

    @Query("UPDATE domains SET status = :status, lastVisited = :time, visitCount = visitCount + 1 WHERE id = :id")
    suspend fun updateDomainStatus(id: Int, status: String, time: Long)

    @Query("SELECT COUNT(*) FROM domains")
    suspend fun getDomainCount(): Int
}
