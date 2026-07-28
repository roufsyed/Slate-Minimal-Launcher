package com.slate.launcher

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class SlateNotificationService : NotificationListenerService() {

    companion object {
        /**
         * Packages that currently have at least one active notification.
         *
         * Held as an immutable snapshot swapped wholesale rather than a mutable set: readers
         * (the render pass) and writers (these callbacks) then never see a half-updated set,
         * with no locking on either side.
         */
        @Volatile
        var activePackages: Set<String> = emptySet()
            private set

        /**
         * Subset of [activePackages] whose notification is *alerting* - channel importance at
         * least IMPORTANCE_DEFAULT.
         *
         * Silencing a channel in Android's notification settings drops it to IMPORTANCE_LOW,
         * which changes alerting only: the notification is still posted and still delivered
         * here. IMPORTANCE_DEFAULT is also the boundary the system itself uses to split the
         * shade's "Notifications" section from its "Silent" one, so this set means exactly
         * "what Android would treat as alerting".
         */
        @Volatile
        var alertingPackages: Set<String> = emptySet()
            private set

        /** Called on the service thread whenever either set changes. */
        @Volatile var onChange: (() -> Unit)? = null

        /**
         * The set the app list should highlight from, per [PreferencesManager.ignoreSilentNotifications].
         * Filtering happens here at read time rather than at write time so flipping the setting
         * only needs a re-render - the service holds both sets and never has to be told.
         */
        fun highlightedPackages(ignoreSilent: Boolean): Set<String> =
            if (ignoreSilent) alertingPackages else activePackages

        /** Drops a package from both sets, so a launched app's highlight clears immediately. */
        fun clearHighlight(packageName: String) {
            activePackages = activePackages - packageName
            alertingPackages = alertingPackages - packageName
        }
    }

    /**
     * Recompute runs off the main thread. [getActiveNotifications] and [getCurrentRanking] are
     * both binder round trips, these callbacks arrive on the app's main thread, and some apps
     * re-post a notification several times a second (download and playback progress), so doing
     * this inline would put that traffic straight onto the thread the launcher draws on.
     * [onChange]'s only subscriber already marshals itself back to the UI thread.
     */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuild()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuild()

    /**
     * Ranking changes with no post or removal - the user toggling a channel to Silent, or Do
     * Not Disturb starting. Without this the sets would stay stale until that app next posted.
     */
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = rebuild()

    override fun onListenerConnected() = rebuild()

    override fun onListenerDisconnected() {
        activePackages = emptySet()
        alertingPackages = emptySet()
        onChange?.invoke()
    }

    /** Queues a [recompute]; rejected only once the service is tearing down. */
    private fun rebuild() {
        try { worker.execute(::recompute) } catch (_: RejectedExecutionException) { }
    }

    /**
     * Recomputes both sets from scratch, rather than adding and removing incrementally.
     *
     * Incremental would avoid the two binder calls, but it cannot stay correct: an app can
     * re-post the same key on a quieter channel, and the user can silence a channel while
     * Slate sits open, either of which strands a stale entry in [alertingPackages] that
     * nothing would ever clear. Nothing tests this, so the version that cannot drift wins.
     */
    private fun recompute() {
        val notifs = try { activeNotifications } catch (_: Exception) { null } ?: return
        val ranking = try { currentRanking } catch (_: Exception) { null }
        val scratch = Ranking()

        val nextActive = HashSet<String>()
        val nextAlerting = HashSet<String>()
        notifs.forEach { sbn ->
            nextActive.add(sbn.packageName)
            // A key missing from the ranking map means we cannot tell how loud it is. Fail
            // toward alerting, so the setting never hides something the user didn't quiet.
            val alerting = ranking == null ||
                !ranking.getRanking(sbn.key, scratch) ||
                scratch.importance >= NotificationManager.IMPORTANCE_DEFAULT
            if (alerting) nextAlerting.add(sbn.packageName)
        }

        if (activePackages == nextActive && alertingPackages == nextAlerting) return
        activePackages = nextActive
        alertingPackages = nextAlerting
        onChange?.invoke()
    }
}
