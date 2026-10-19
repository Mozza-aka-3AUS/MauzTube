package com.mauztube.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * DNS con memoria: gli indirizzi di YouTube cambiano di rado, quindi un indirizzo già
 * risolto viene riusato subito (e aggiornato in background dopo 5 minuti).
 *  - VELOCITÀ: niente attesa del DNS per ogni nuova connessione;
 *  - AFFIDABILITÀ: se il DNS non risponde (rete mobile instabile: misurati blocchi di 10–17 s)
 *    si usano gli ultimi indirizzi validi invece di fallire.
 */
class CachingDns : Dns {
    private class Entry(val addresses: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val refresher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "dns-refresh").apply { isDaemon = true } }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val entry = cache[hostname]
        val now = SystemClock.elapsedRealtime()
        if (entry != null && now - entry.at < MAX_STALE_MS) {
            if (now - entry.at > FRESH_MS && refreshing.add(hostname)) {
                refresher.execute {
                    try { resolve(hostname) } catch (_: Exception) { } finally { refreshing.remove(hostname) }
                }
            }
            return entry.addresses
        }
        return try {
            resolve(hostname)
        } catch (e: UnknownHostException) {
            entry?.addresses ?: throw e
        }
    }

    private fun resolve(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).also {
            if (cache.size > MAX_HOSTS) cache.clear()
            cache[hostname] = Entry(it, SystemClock.elapsedRealtime())
        }

    /** Cambiando rete (Wi-Fi ⇄ dati) i server più vicini possono essere altri. */
    fun clear() = cache.clear()

    private companion object {
        const val FRESH_MS = 5 * 60_000L
        const val MAX_STALE_MS = 60 * 60_000L
        const val MAX_HOSTS = 128
    }
}

/**
 * Al cambio di rete le connessioni aperte sulla rete precedente sono morte ma restano nel
 * pool: la richiesta successiva le riuserebbe e resterebbe ferma fino al timeout. Qui si
 * chiudono subito.
 */
object NetworkWatcher {
    private var current: Network? = null

    fun start(context: Context, clients: List<OkHttpClient>, dns: CachingDns) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    if (previous != null && previous != network) reset("rete cambiata")
                }

                override fun onLost(network: Network) {
                    if (network == current) reset("rete persa")
                }

                private fun reset(why: String) {
                    Log.i("NetworkWatcher", "Connessioni azzerate: $why")
                    clients.forEach { it.connectionPool.evictAll() }
                    dns.clear()
                }
            })
        }.onFailure { Log.w("NetworkWatcher", "Monitoraggio rete non disponibile", it) }
    }
}
