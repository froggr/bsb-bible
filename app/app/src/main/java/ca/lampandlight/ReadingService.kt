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
        when (intent?.action) {
            ACTION_STOP -> {
                // The notification's own Stop: silence everything and stand down.
                hold?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Reading"
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: ""
        startForeground(ID, build(title, detail))
        // The voice is made on this phone a clause ahead of where it is heard, so the
        // processor has to stay up between clauses, not only while sound is leaving the
        // speaker. Playing audio keeps it up only as long as there is audio to play: one
        // clause that arrives late lets the phone doze, and then the next is later still.
        // Asked for again on every start, which each new chapter is, so the limit is on one
        // chapter and not on the reading; the limit is there so a fault cannot hold it for ever.
        runCatching {
            val lock = awake ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lampandlight:reading")
                .apply { setReferenceCounted(false) }.also { awake = it }
            lock.acquire(3 * 60 * 60 * 1000L)
        }
        return START_STICKY
    }

    private var awake: PowerManager.WakeLock? = null

    override fun onDestroy() {
        runCatching { awake?.release() }
        awake = null
        super.onDestroy()
    }

    private fun build(title: String, detail: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReadingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon_(android.R.drawable.ic_media_pause), "Stop", stop,
                ).build()
            )
        return b.build()
    }

    @Suppress("FunctionName")
    private fun Icon_(res: Int) = android.graphics.drawable.Icon.createWithResource(this, res)

    companion object {
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

        fun start(ctx: Context, title: String, detail: String) {
            val i = Intent(ctx, ReadingService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL, detail)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ReadingService::class.java))
        }
    }
}
