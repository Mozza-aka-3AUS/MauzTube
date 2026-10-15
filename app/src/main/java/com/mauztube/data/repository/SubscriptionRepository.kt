package com.mauztube.data.repository

import android.util.Log
import com.mauztube.data.account.AccountManager
import com.mauztube.data.local.SubscriptionDao
import com.mauztube.data.local.SubscriptionEntity
import com.mauztube.data.prefs.SettingsRepository
import com.mauztube.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Iscrizioni. Senza account sono solo locali. Con l'account collegato:
 *  - iscriversi/disiscriversi agisce anche sull'account YouTube;
 *  - [sync] allinea la lista locale a quella dell'account. Al PRIMO allineamento dopo il
 *    login le iscrizioni fatte in locale vengono anche aggiunte all'account (nessuna si perde);
 *    dopo, l'account è la fonte di verità.
 */
@Singleton
class SubscriptionRepository @Inject constructor(
    private val dao: SubscriptionDao,
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    val subscriptions: Flow<List<SubscriptionEntity>> = dao.observeAll()

    fun isSubscribed(channelUrl: String): Flow<Boolean> = dao.observeIsSubscribed(channelUrl)

    suspend fun all(): List<SubscriptionEntity> = dao.all()

    private val syncLock = Mutex()
    private var lastSync = 0L

    suspend fun subscribe(channelUrl: String, name: String, avatarUrl: String?) {
        dao.upsert(SubscriptionEntity(channelUrl, name, avatarUrl, System.currentTimeMillis()))
        channelIdOf(channelUrl)?.let { id -> remote { recommendations.subscribe(id) } }
    }

    suspend fun unsubscribe(channelUrl: String) {
        dao.delete(channelUrl)
        channelIdOf(channelUrl)?.let { id -> remote { recommendations.unsubscribe(id) } }
    }

    /** @param force ignora il limite di una sincronizzazione ogni 30 minuti. */
    suspend fun sync(force: Boolean = false) = syncLock.withLock {
        if (!account.loggedIn.value) return@withLock
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 30 * 60_000L) return@withLock
        val remoteList = runCatching { recommendations.accountSubscriptions() }
            .onFailure { Log.w(TAG, "Lettura iscrizioni account fallita", it) }
            .getOrNull() ?: return@withLock
        lastSync = now
        val remoteIds = remoteList.map { it.channelId }.toSet()
        val local = dao.all()

        if (!settings.subscriptionsMerged()) {
            // Primo allineamento: le iscrizioni locali vengono portate sull'account.
            local.mapNotNull { channelIdOf(it.channelUrl) }.filter { it !in remoteIds }.forEach { id ->
                runCatching { recommendations.subscribe(id) }
            }
            settings.setSubscriptionsMerged(true)
        } else {
            // Poi l'account è la fonte di verità: si tolgono quelle annullate altrove.
            local.filter { channelIdOf(it.channelUrl)?.let { id -> id !in remoteIds } == true }
                .forEach { dao.delete(it.channelUrl) }
        }
        remoteList.forEach { c ->
            dao.upsert(SubscriptionEntity(channelUrlOf(c.channelId), c.name, c.avatarUrl, now))
        }
    }

    private fun remote(block: suspend () -> Unit) {
        if (!account.loggedIn.value) return
        appScope.launch { runCatching { block() }.onFailure { Log.w(TAG, "Azione iscrizione su account fallita", it) } }
    }

    companion object {
        private const val TAG = "SubscriptionRepository"
        fun channelUrlOf(channelId: String) = "https://www.youtube.com/channel/$channelId"
        fun channelIdOf(url: String): String? =
            url.substringAfter("/channel/", "").substringBefore('/').substringBefore('?').takeIf { it.startsWith("UC") }
    }
}
