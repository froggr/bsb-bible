package ca.lampandlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the reading alive when the screen goes off.
 *
 * Android stops an app's audio shortly after it leaves the foreground unless a service is
 * holding it up, which is why narration used to die the moment the phone was pocketed — the
 * one time someone most wants to be read to. Nothing is played here: the Narrator and the
 * Bed still own the audio. This exists so the system lets them carry on, and so there is a
 * notification to stop from without unlocking.
 */
class ReadingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        asked = false
        if (intent?.action == ACTION_STOP) {
            // The notification's own Stop: silence everything and stand down.
            hold?.invoke()
            down()
            return START_NOT_STICKY
        }
        // No intent means Android has brought the service back by itself after the app was
        // removed from memory. There is nothing being read in a new process; go away.
        if (intent == null) { down(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reading"
        val detail = intent.getStringExtra(EXTRA_DETAIL) ?: ""
        // Refused when the app is not allowed to be in the foreground at this moment. That
        // costs the notification and, in time, the audio; it must not cost the app.
        val up = runCatching { startForeground(ID, build(this, title, detail)) }
            .onFailure { Log.w(TAG, "could not hold the reading in the foreground: $it") }
            .isSuccess
        if (!up || !wanted) { down(); return START_NOT_STICKY }
        running = true
        stayAwake(this)
        // Not sticky: brought back without the activity there would be a notification
        // saying "Reading" over a silent phone.
        return START_NOT_STICKY
    }

    private fun down() {
        running = false
        letSleep()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        letSleep()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "lamplight"
        private const val CHANNEL = "reading"
        private const val ID = 1
        private const val ACTION_STOP = "ca.lampandlight.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAIL = "detail"

        /**
         * What to run when the notification's Stop is pressed.
         *
         * The service does not own the Narrator, so it cannot stop it directly; the activity
         * leaves this behind. Static because the system may hand the intent to a fresh
         * service instance.
         */
        @Volatile var hold: (() -> Unit)? = null

        // Up, with its notification showing.
        @Volatile private var running = false
        // Asked for, and not yet arrived at onStartCommand.
        @Volatile private var asked = false
        // Whether anybody still wants it. A service asked for must be allowed to come up
        // before it is stopped — stopping it sooner is an error Android ends the app for —
        // so a stop that arrives in between is left here for the service to act on.
        @Volatile private var wanted = false
        private var awake: PowerManager.WakeLock? = null

        /**
         * Hold the reading up, and say what is being read.
         *
         * Started once and then only relabelled. It used to be started again for every
         * change of title, and stopped whenever nothing was sounding, which the end of a
         * chapter is for a moment. Android does not let an app start a foreground service
         * unless it is on screen or one is already running; with the phone locked and no
         * music holding the service up, the start for the next chapter was refused and the
         * refusal ended the app. So: one start, made while the reader is looking, and
         * nothing afterwards that needs permission.
         */
        fun start(ctx: Context, title: String, detail: String) {
            wanted = true
            if (running) {
                runCatching {
                    ctx.getSystemService(NotificationManager::class.java)
                        .notify(ID, build(ctx, title, detail))
                }
                stayAwake(ctx)
                return
            }
            val i = Intent(ctx, ReadingService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL, detail)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                asked = true
            }.onFailure { Log.w(TAG, "could not start the reading service: $it") }
        }

        fun stop(ctx: Context) {
            wanted = false
            // On its way up: it will see that it is not wanted and stand down by itself.
            if (asked) return
            running = false
            letSleep()
            ctx.stopService(Intent(ctx, ReadingService::class.java))
        }

        /**
         * The voice is made on this phone ahead of where it is heard, so the processor has
         * to stay up between clauses, not only while sound is leaving the speaker. Playing
         * audio keeps it up only as long as there is audio to play: one clause that arrives
         * late lets the phone doze, and then the next is later still. Asked for again at
         * every change of chapter, so the limit is on one chapter and not on the reading;
         * the limit is there so a fault cannot hold it for ever.
         */
        private fun stayAwake(ctx: Context) {
            runCatching {
                val lock = awake ?: ctx.applicationContext.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lampandlight:reading")
                    .apply { setReferenceCounted(false) }.also { awake = it }
                lock.acquire(3 * 60 * 60 * 1000L)
            }
        }

        private fun letSleep() {
            runCatching { awake?.takeIf { it.isHeld }?.release() }
        }

        private fun build(ctx: Context, title: String, detail: String): Notification {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW)
                        .apply {
                            description = "Shown while the Bible is being read aloud."
                            setShowBadge(false)
                        }
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                ctx, 1, Intent(ctx, ReadingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(ctx, CHANNEL)
                .setContentTitle(title)
                .setContentText(detail)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            ctx, android.R.drawable.ic_media_pause),
                        "Stop", stop,
                    ).build()
                )
                .build()
        }
    }
}
