package ai.impact.data.checkins

import ai.impact.ImpactApp
import ai.impact.MainActivity
import ai.impact.R
import ai.impact.data.BuiltInPersonas
import ai.impact.data.Persona
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Check-in notifications: a friendly nudge about something the user was exploring, sent at the
 * time of day they usually chat after they've been away. Everything runs on the device: topics
 * come from local chat history and the alarm is local, so nothing is sent anywhere.
 *
 * See [CheckInSchedule] for when they go out and [CheckInComposer] for what they say.
 */
class CheckIns(private val app: ImpactApp) {

    companion object {
        private const val TAG = "CheckIns"
        private const val FILE = "check_ins"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OPENS = "recent_opens"
        private const val KEY_LAST_OPEN = "last_open_at"
        private const val KEY_LEFT_AT = "left_at"
        private const val KEY_STEP = "step"
        private const val KEY_NEXT_AT = "next_at"
        private const val KEY_VARIANT = "variant"
        private const val KEY_ASKED_PERMISSION = "asked_permission"
        const val CHANNEL_ID = "check_ins"
        private const val NOTIFICATION_ID = 7_001
        private const val ALARM_REQUEST = 7_002

        /** Extras on the intent that opens the app from a check-in. */
        const val EXTRA_CONVERSATION_ID = "check_in_conversation_id"
        const val EXTRA_DRAFT = "check_in_draft"

        private const val MAX_OPENS = 20
        /** Opens closer together than this are one session (rotation, a quick app switch). */
        private const val SESSION_GAP = 30 * 60_000L
        /** Only topics from the last few weeks are still worth bringing up. */
        private const val TOPIC_WINDOW = 30L * 24 * 60 * 60_000L
    }

    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val notificationsAllowed: Boolean get() = NotificationManagerCompat.from(app).areNotificationsEnabled()

    /** Whether the app has asked for notification permission on behalf of check-ins yet. */
    var askedPermission: Boolean
        get() = prefs.getBoolean(KEY_ASKED_PERMISSION, false)
        set(value) { prefs.edit().putBoolean(KEY_ASKED_PERMISSION, value).apply() }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        if (!enabled) cancel()
    }

    /** The user is back: pending check-ins are dropped, and this visit counts toward their usual time. */
    fun onAppForeground(now: Long = System.currentTimeMillis()) {
        cancel()
        val lastOpen = prefs.getLong(KEY_LAST_OPEN, 0L)
        val edit = prefs.edit().putLong(KEY_LAST_OPEN, now)
        if (now - lastOpen > SESSION_GAP) {
            val opens = (readOpens() + CheckInSchedule.minuteOfDay(now)).takeLast(MAX_OPENS)
            edit.putString(KEY_OPENS, JSONArray(opens).toString())
        }
        edit.apply()
    }

    /** The user left: line up the first check-in for when they'd usually be back. */
    fun onAppBackground(now: Long = System.currentTimeMillis()) {
        if (!_enabled.value || !app.preferences.onboardingComplete.value) return
        prefs.edit().putLong(KEY_LEFT_AT, now).putInt(KEY_STEP, 0).apply()
        scheduleStep(0)
    }

    /** After a restart the system has forgotten the alarm; set it again. */
    fun restore() {
        val next = prefs.getLong(KEY_NEXT_AT, 0L)
        if (next == 0L || !_enabled.value) return
        // One that came due while the phone was off goes out shortly after boot.
        setAlarm(maxOf(next, System.currentTimeMillis() + 60_000L))
    }

    fun reset() {
        cancel()
        prefs.edit().clear().apply()
        _enabled.value = true
    }

    /** Shows the check-in that's due, then lines up the next one. */
    suspend fun deliver() {
        val step = prefs.getInt(KEY_STEP, 0)
        prefs.edit().remove(KEY_NEXT_AT).apply()
        if (!_enabled.value || !app.preferences.onboardingComplete.value) return
        try {
            if (notificationsAllowed) show(step)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't show a check-in", e)
        }
        prefs.edit().putInt(KEY_STEP, step + 1).apply()
        scheduleStep(step + 1)
    }

    private suspend fun show(step: Int) {
        val personas = BuiltInPersonas.all + app.preferences.customPersonas.value
        fun personaFor(id: String): Persona? = personas.firstOrNull { it.id == id }

        val since = System.currentTimeMillis() - TOPIC_WINDOW
        val messages = app.chatHistory.recentUserMessages(since).map {
            SentMessage(it.text, it.conversationId, it.personaId, it.createdAt)
        }
        val topics = CheckInComposer.topics(messages, ::personaFor)
        // Each check-in in a row brings up a different recent topic.
        val topic = topics.getOrNull(step % maxOf(topics.size, 1))
        val selected = personaFor(app.preferences.selectedPersonaId.value) ?: BuiltInPersonas.all.first()
        val persona = topic?.let { personaFor(it.personaId) } ?: selected

        val variant = prefs.getInt(KEY_VARIANT, 0)
        prefs.edit().putInt(KEY_VARIANT, variant + 1).apply()
        val message = CheckInComposer.compose(topic, persona, app.preferences.userName.value, variant)

        ensureChannel()
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CONVERSATION_ID, topic?.conversationId ?: 0L)
                .putExtra(EXTRA_DRAFT, message.prompt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // The lock screen gets a version that doesn't reveal what the user was chatting about.
        val public = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check_in)
            .setContentTitle("${persona.emoji} ${persona.name} has something for you")
            .setColor(persona.colorStart.toInt())
            .build()
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check_in)
            .setLargeIcon(avatar(persona))
            .setColor(persona.colorStart.toInt())
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setSubText(persona.name)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission was revoked", e)
        }
    }

    private fun scheduleStep(step: Int) {
        val leftAt = prefs.getLong(KEY_LEFT_AT, System.currentTimeMillis())
        val opens = readOpens()
        val last = CheckInSchedule.minuteOfDay(prefs.getLong(KEY_LAST_OPEN, leftAt))
        val minute = CheckInSchedule.habitualMinute(opens, last)
        val at = CheckInSchedule.triggerAt(leftAt, step, minute)
        if (at == null) {
            cancelAlarm()
            return
        }
        prefs.edit().putLong(KEY_NEXT_AT, at).apply()
        setAlarm(at)
    }

    private fun setAlarm(at: Long) {
        // A check-in doesn't need to be on the minute; a window lets the system save battery.
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 15 * 60_000L, alarmIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return)
    }

    private fun cancel() {
        cancelAlarm()
        prefs.edit().remove(KEY_NEXT_AT).putInt(KEY_STEP, 0).apply()
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    private fun cancelAlarm() {
        alarmIntent(PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun alarmIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        app, ALARM_REQUEST, Intent(app, CheckInReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Check-ins", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A friendly nudge about what you were exploring, around the time you usually chat"
            }
        )
    }

    private fun readOpens(): List<Int> = try {
        val array = JSONArray(prefs.getString(KEY_OPENS, "[]"))
        List(array.length()) { array.getInt(it) }
    } catch (e: Exception) {
        emptyList()
    }

    /** The persona's gradient avatar with its emoji, as the notification's picture. */
    private fun avatar(persona: Persona): Bitmap {
        val size = (64 * app.resources.displayMetrics.density).toInt().coerceAtLeast(96)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                persona.colorStart.toInt(), persona.colorEnd.toInt(), Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle)
        val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (emoji.descent() + emoji.ascent()) / 2f
        canvas.drawText(persona.emoji, size / 2f, y, emoji)
        return bitmap
    }
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ImpactApp
        // Reading chat history takes a moment, so keep the receiver alive until it's done.
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.checkIns.deliver()
            } finally {
                pending.finish()
            }
        }
    }
}
