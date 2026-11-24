package dev.deniz.agentpresence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Keeps the process alive while a task runs and carries the ongoing notification.
 * Started only after the overlay window is up: on Android 15+ a background app may start a foreground
 * service only while it shows an overlay window.
 */
class PresenceService : Service() {
    companion object {
        private const val TAG = "AgentPresence"
        @Volatile private var instance: PresenceService? = null

        private val main = Handler(Looper.getMainLooper())
        private var retrying = false

        /** Main thread. */
        internal fun sync(c: Context) = sync(c, 0)

        private fun sync(c: Context, attempt: Int) {
            // Only live sessions need the service: a finished one stays on the overlay until closed, and its
            // own "Bitti" notification is already posted, so the ongoing one would only repeat it.
            val list = AgentPresence.sessions().filter { it.live }
            val running = instance
            if (list.isEmpty()) {
                running?.finish()
                Notifier.cancelOngoing(c)
                return
            }
            if (running != null) {
                running.refresh()
                return
            }
            if (attempt == 0 && retrying) return
            try {
                c.startForegroundService(Intent(c, PresenceService::class.java))
                retrying = false
            } catch (e: Exception) {
                // From the background the start is allowed only once the overlay window is actually visible, which
                // is a frame or two after addView. Retry for a while; without overlay permission it never will be.
                if (Overlay.isShowing() && attempt < 12) {
                    retrying = true
                    main.postDelayed({ sync(c, attempt + 1) }, 250)
                } else {
                    retrying = false
                    Log.w(TAG, "foreground service not allowed: ${e.message}")
                    Notifier.postOngoing(c)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentPresence.init(this)
        if (AgentPresence.sessions().none { it.live }) {
            finish()
            return START_NOT_STICKY
        }
        try {
            startForeground(Notifier.ONGOING_ID, Notifier.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            Notifier.postOngoing(this)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    internal fun refresh() = Notifier.postOngoing(this)

    internal fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

/** Notification taps: Durdur and Göster. Not exported: only our own PendingIntents reach it. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifier.EXTRA_ID) ?: return
        when (intent.action) {
            Notifier.ACTION_STOP -> AgentPresence.requestStop(context.applicationContext, id)
            Notifier.ACTION_SHOW -> AgentPresence.send(context, PresenceEvent(id = id, type = "expand"))
        }
    }
}

internal object Notifier {
    const val ONGOING_ID = 0x4150
    const val ACTION_STOP = "dev.deniz.agentpresence.STOP"
    const val ACTION_SHOW = "dev.deniz.agentpresence.SHOW"
    const val EXTRA_ID = "id"
    private const val CH_LIVE = "agentpresence_live"
    private const val CH_END = "agentpresence_end"

    private fun channels(c: Context): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_LIVE) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, Strings.channel, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel(CH_END) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_END, Strings.channelEnd, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            })
        }
        return nm
    }

    fun postOngoing(c: Context) {
        val nm = channels(c)
        runCatching { nm.notify(ONGOING_ID, ongoing(c)) }
    }

    fun cancelOngoing(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(ONGOING_ID)
    }

    private fun pending(c: Context, action: String, id: String, code: Int) = PendingIntent.getBroadcast(
        c, code,
        Intent(c, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun color(s: Session) = when (s.phase) {
        Phase.RUNNING -> Palette.BLUE
        Phase.WAITING -> Palette.AMBER
        Phase.DONE -> Palette.GREEN
        Phase.FAILED -> Palette.RED
    }

    /** The one ongoing notification: the main session, as a Live Update where the system supports it. */
    fun ongoing(c: Context): Notification {
        channels(c)
        val list = AgentPresence.sessions().filter { it.live }
        val s = Overlay.primaryOf(list) ?: list.lastOrNull()
        val b = Notification.Builder(c, CH_LIVE)
            .setSmallIcon(R.drawable.agentpresence_ic_stat)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (s == null) return b.setContentTitle(Strings.channel).build()

        val name = listOfNotNull(s.agent, s.app).joinToString(" · ")
        val text = when (s.phase) {
            Phase.RUNNING -> listOfNotNull(s.stepLabel ?: s.title, s.detail).joinToString(" · ")
            Phase.WAITING -> s.message ?: Strings.waiting
            Phase.DONE -> s.message ?: Strings.done
            Phase.FAILED -> s.message ?: Strings.failed
        }
        // The computed directives (SPEC "Directives on screen"), only while the agent is running. Plain words, no
        // emoji (SPEC §4): "Spotify'ı aç · Ekrana dokunma".
        val lines = buildList {
            add(text)
            for (d in s.directivesOn("phone")) add(d.text)
        }
        b.setContentTitle(
            when (s.phase) {
                Phase.WAITING -> "${Strings.waiting}: $name"
                Phase.DONE -> "${Strings.done}: $name"
                Phase.FAILED -> "${Strings.failed}: $name"
                Phase.RUNNING -> name
            },
        )
            .setContentText(lines.filter { it.isNotBlank() }.joinToString(" · "))
            .setWhen(s.started)
            .setColor(color(s))
            .setOngoing(s.live)
            .setContentIntent(pending(c, ACTION_SHOW, s.id, 1))
        val sub = buildList {
            if (s.total > 0 && s.live) add(Strings.stepOf(s.step, s.total))
            if (list.size > 1) add("+${list.size - 1}")
        }.joinToString(" · ")
        if (sub.isNotEmpty()) b.setSubText(sub)
        if (s.live && !s.stopRequested) {
            b.addAction(Notification.Action.Builder(null, Strings.stop, pending(c, ACTION_STOP, s.id, 2)).build())
        }
        b.addAction(Notification.Action.Builder(null, Strings.show, pending(c, ACTION_SHOW, s.id, 3)).build())

        if (Build.VERSION.SDK_INT >= 36 && s.live) {
            val style = Notification.ProgressStyle().setStyledByProgress(true)
            if (s.total > 0) {
                style.setProgressSegments((1..s.total).map { Notification.ProgressStyle.Segment(100).setColor(color(s)) })
                style.setProgress(s.finished * 100)
            } else {
                style.setProgressIndeterminate(true)
            }
            b.setStyle(style)
            b.setShortCriticalText(
                when {
                    s.phase == Phase.WAITING -> "!"
                    !s.detail.isNullOrBlank() -> s.detail
                    s.total > 0 -> "${s.step}/${s.total}"
                    else -> s.app ?: s.agent
                },
            )
            // Notification.Builder#setRequestPromotedOngoing is API 36.1; the extra is what it sets.
            b.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        return b.build()
    }

    /** A separate, dismissable "it is over" notification, so the end is not missed (SPEC: never leave them waiting). */
    fun finished(c: Context, s: Session) {
        val nm = channels(c)
        val name = listOfNotNull(s.agent, s.app).joinToString(" · ")
        val ok = s.phase == Phase.DONE
        val n = Notification.Builder(c, CH_END)
            .setSmallIcon(R.drawable.agentpresence_ic_stat)
            .setContentTitle("${if (ok) Strings.done else Strings.failed}: $name")
            .setContentText(s.message ?: s.title ?: "")
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(s.message, s.title).joinToString("\n")))
            .setColor(color(s))
            .setAutoCancel(true)
            .setContentIntent(pending(c, ACTION_SHOW, s.id, 4))
            .build()
        runCatching { nm.notify(finishedId(s.id), n) }
    }

    /** The owner closed the session on the overlay (or it was restarted / ended): its "Bitti" note goes too. */
    fun cancelFinished(c: Context?, id: String) {
        c ?: return
        c.getSystemService(NotificationManager::class.java).cancel(finishedId(id))
    }

    private fun finishedId(id: String) = 0x4151 + (id.hashCode() and 0xFFF)
}
