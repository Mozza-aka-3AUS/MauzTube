package com.mauztube.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mauztube.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Servizio di sistema per la riproduzione fuori dall'app e a schermo spento.
 * Espone il player di [PlaybackManager] tramite MediaSession: Media3 crea la notifica con i
 * controlli (play/pausa, avanzamento), gestisce cuffie/auto/lockscreen e porta il servizio in
 * foreground mentre si riproduce.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var manager: PlaybackManager

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, manager.player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** App rimossa dai recenti: se non sta suonando non ha senso restare in vita. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // App chiusa dai recenti: la coda non sopravvive alla chiusura.
        manager.clearQueue()
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            manager.savePosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release() // il player appartiene a PlaybackManager e non viene rilasciato qui
        session = null
        super.onDestroy()
    }
}
