package com.gabriel.gamedrop.events

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gabriel.gamedrop.R
import com.gabriel.gamedrop.data.events.EventFeed
import com.gabriel.gamedrop.data.events.GamingEvent
import com.google.gson.Gson
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.max

object EventNotificationCoordinator {
    private const val PREFS = "event_notifications"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_KNOWN_IDS = "known_ids"
    private const val CHANNEL_ID = "gaming_events"
    private const val PERIODIC_WORK = "gaming-events-sync"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) ensurePeriodicSync(context) else WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    fun ensurePeriodicSync(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<EventSyncWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun reconcile(context: Context, events: List<GamingEvent>, announceNew: Boolean = true) {
        if (!isEnabled(context)) return
        createChannel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
        val known = prefs.getStringSet(KEY_KNOWN_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val now = Instant.now()
        val future = events.filter { event -> runCatching { Instant.parse(event.startTime).isAfter(now) }.getOrDefault(false) }

        if (initialized && announceNew) {
            future.filter { it.id.toString() !in known }.forEach { showNewEventNotification(context, it) }
        }

        future.forEach { scheduleReminder(context, it) }
        known += events.map { it.id.toString() }
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putStringSet(KEY_KNOWN_IDS, known)
            .apply()
    }

    private fun scheduleReminder(context: Context, event: GamingEvent) {
        val start = runCatching { Instant.parse(event.startTime) }.getOrNull() ?: return
        val reminderAt = start.minus(Duration.ofHours(1))
        val delayMs = max(0L, Duration.between(Instant.now(), reminderAt).toMillis())
        val data = Data.Builder()
            .putLong("event_id", event.id)
            .putString("event_name", event.name)
            .putString("event_start", event.startTime)
            .putString("event_stream", event.liveStreamUrl)
            .build()
        val request = OneTimeWorkRequestBuilder<EventReminderWorker>()
            .setInputData(data)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "gaming-event-reminder-${event.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun showNewEventNotification(context: Context, event: GamingEvent) {
        if (!canNotify(context)) return
        val openApp = launchAppIntent(context, event.id)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.event_notification_new_title))
            .setContentText(context.getString(R.string.event_notification_new_body, event.name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.event_notification_new_body, event.name)))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        addStreamAction(context, builder, event)
        NotificationManagerCompat.from(context).notify((event.id % Int.MAX_VALUE).toInt(), builder.build())
    }

    internal fun showReminderNotification(context: Context, eventId: Long, name: String, streamUrl: String?) {
        if (!isEnabled(context) || !canNotify(context)) return
        createChannel(context)
        val openApp = launchAppIntent(context, eventId)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.event_notification_soon_title))
            .setContentText(context.getString(R.string.event_notification_soon_body, name))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (!streamUrl.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(streamUrl))
            val pending = PendingIntent.getActivity(
                context,
                (eventId % Int.MAX_VALUE).toInt() + 100000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, context.getString(R.string.events_watch_live), pending)
        }
        NotificationManagerCompat.from(context).notify(((eventId + 500000) % Int.MAX_VALUE).toInt(), builder.build())
    }

    private fun addStreamAction(context: Context, builder: NotificationCompat.Builder, event: GamingEvent) {
        val url = event.liveStreamUrl ?: return
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pending = PendingIntent.getActivity(
            context,
            (event.id % Int.MAX_VALUE).toInt() + 200000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, context.getString(R.string.events_watch_live), pending)
    }

    private fun launchAppIntent(context: Context, eventId: Long): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("event_id", eventId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: return null
        return PendingIntent.getActivity(
            context,
            (eventId % Int.MAX_VALUE).toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.event_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.event_notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}

class EventReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getLong("event_id", -1L)
        val name = inputData.getString("event_name") ?: return Result.failure()
        val stream = inputData.getString("event_stream")
        if (eventId < 0) return Result.failure()
        EventNotificationCoordinator.showReminderNotification(applicationContext, eventId, name, stream)
        return Result.success()
    }
}

class EventSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!EventNotificationCoordinator.isEnabled(applicationContext)) return Result.success()
        return runCatching {
            val json = URL("https://raw.githubusercontent.com/Gabriel-Liz2003/teste10900/main/data/events-feed.json").readText()
            val feed = Gson().fromJson(json, EventFeed::class.java)
            EventNotificationCoordinator.reconcile(applicationContext, feed.events, announceNew = true)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
