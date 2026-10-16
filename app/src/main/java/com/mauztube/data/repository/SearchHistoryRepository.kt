package com.mauztube.data.repository

import com.mauztube.data.account.AccountManager
import com.mauztube.data.local.SearchHistoryDao
import com.mauztube.data.local.SearchHistoryEntity
import com.mauztube.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Ricerche recenti: salvate solo se l'opzione è attiva e la modalità anonimo è spenta. */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val dao: SearchHistoryDao,
    private val settings: SettingsRepository,
    private val account: AccountManager,
) {
    val recent: Flow<List<String>> = dao.observeRecent()

    suspend fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty() || account.incognito.value || !settings.current().saveSearchHistory) return
        dao.upsert(SearchHistoryEntity(q, System.currentTimeMillis()))
        dao.trim()
    }

    suspend fun delete(query: String) = dao.delete(query)
    suspend fun clear() = dao.clear()
}
