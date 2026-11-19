package ai.impact.data.skills

import ai.impact.ImpactApp
import ai.impact.MainActivity
import ai.impact.R
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledNotification(val id: Int, val title: String, val triggerAt: Long)

/**
 * Notifications scheduled by the "Schedule a notification" skill. They're kept on disk because
 * the system forgets alarms when the phone restarts; [BootReceiver] sets them again.
 */
class Reminders(context: Context) {

    companion object {
        private const val TAG = "Reminders"
        private const val FILE = "skill_reminders"
        private const val KEY = "scheduled"
        const val CHANNEL_ID = "reminders"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val alarms = appContext.getSystemService(AlarmManager::class.java)

    private val _scheduled = MutableStateFlow(read())
    val scheduled: StateFlow<List<ScheduledNotification>> = _scheduled.asStateFlow()

    val notificationsEnabled: Boolean get() = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    fun schedule(title: String, triggerAt: Long): ScheduledNotification {
        val item = ScheduledNotification((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), title, triggerAt)
        write(_scheduled.value + item)
        setAlarm(item)
        return item
    }

    fun cancel(id: Int) {
        // Pending intents match on component and request code, so the title doesn't matter here.
        pendingIntent(id, "", PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
        remove(id)
    }

    fun remove(id: Int) = write(_scheduled.value.filterNot { it.id == id })

    /** After a restart: sets future alarms again and shows any that came due while the phone was off. */
    fun restore() {
        val now = System.currentTimeMillis()
        _scheduled.value.forEach { if (it.triggerAt > now) setAlarm(it) else show(it.id, it.title) }
    }

    fun show(id: Int, title: String) {
        remove(id)
        ensureChannel()
        val open = PendingIntent.getActivity(
            appContext, id,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText("Your TinyAI reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission was revoked", e)
        }
    }

    private fun setAlarm(item: ScheduledNotification) {
        val intent = pendingIntent(item.id, item.title, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, intent)
        } else {
            // Without the exact-alarm permission, ask for a ten-minute window the system can batch.
            alarms.setWindow(AlarmManager.RTC_WAKEUP, item.triggerAt, 10 * 60_000L, intent)
        }
    }

    private fun pendingIntent(id: Int, title: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext, id,
        Intent(appContext, ReminderReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title),
        flags or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications you scheduled from the chat"
            }
        )
    }

    private fun write(list: List<ScheduledNotification>) {
        val json = JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("at", it.triggerAt)) }
        }
        prefs.edit().putString(KEY, json.toString()).apply()
        _scheduled.value = list
    }

    private fun read(): List<ScheduledNotification> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) {
            val o = array.getJSONObject(it)
            ScheduledNotification(o.getInt("id"), o.getString("title"), o.getLong("at"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read scheduled notifications", e)
        emptyList()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, 0)
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE) ?: "Reminder"
        (context.applicationContext as ImpactApp).reminders.show(id, title)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        (context.applicationContext as ImpactApp).reminders.restore()
    }
}
