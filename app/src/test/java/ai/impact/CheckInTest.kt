package ai.impact

import ai.impact.data.BuiltInPersonas
import ai.impact.data.checkins.CheckInComposer
import ai.impact.data.checkins.CheckInSchedule
import ai.impact.data.checkins.SentMessage
import ai.impact.data.checkins.Topic
import ai.impact.data.checkins.TopicExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class CheckInTest {

    private fun topic(text: String) = TopicExtractor.extract(text)

    @Test
    fun subjectsComeOutAsAPhrase() {
        assertEquals("black holes", topic("Tell me about black holes")?.display)
        assertEquals("black hole", topic("What is a black hole?")?.display)
        assertEquals("Stoicism", topic("Can you teach me some Stoicism please")?.raw?.removePrefix("some "))
        assertEquals("world capitals", topic("Quiz me on world capitals")?.display)
        assertEquals("gift ideas", topic("Brainstorm gift ideas")?.display)
        assertEquals("how vaccines work", topic("Explain how vaccines work")?.display)
        assertEquals(Topic.Kind.Subject, topic("Who was Ada Lovelace?")?.kind)
    }

    @Test
    fun tasksSpeakToTheUser() {
        val plan = topic("Help me plan my day")!!
        assertEquals(Topic.Kind.Task, plan.kind)
        assertEquals("plan my day", plan.raw)
        assertEquals("plan your day", plan.display)
        assertEquals("start running", topic("How do I start running?")?.display)
    }

    @Test
    fun openQuestionsAreQuotedAsAsked() {
        val q = topic("why is the sky blue")!!
        assertEquals(Topic.Kind.Question, q.kind)
        assertEquals("Why is the sky blue?", q.display)
    }

    @Test
    fun smallTalkToolsAndWritingAreSkipped() {
        assertNull(topic("hi"))
        assertNull(topic("thanks a lot"))
        assertNull(topic("ok cool"))
        assertNull(topic("Remind me at 6pm to call mom"))
        assertNull(topic("Make a QR code for example.com"))
        assertNull(topic("Write me a short poem"))
        assertNull(topic("draw a cat in space"))
        assertNull(topic("x".repeat(200)))
    }

    @Test
    fun privateChatsAreNeverQuoted() {
        val messages = listOf(
            SentMessage("Tell me about my anxiety", 1, "therapist", 3),
            SentMessage("Tell me about black holes", 2, "assistant", 2),
            SentMessage("tell me about Black Holes", 3, "assistant", 1)
        )
        val topics = CheckInComposer.topics(messages) { BuiltInPersonas.byId(it) }
        assertEquals(listOf("black holes"), topics.map { it.display })
        assertEquals(2L, topics.single().conversationId)
    }

    @Test
    fun messagesAreWarmAndAddressed() {
        val assistant = BuiltInPersonas.byId("assistant")!!
        val message = CheckInComposer.compose(topic("Tell me about black holes"), assistant, "Sam", 0)
        assertEquals("✨ Still curious about black holes?", message.title)
        assertTrue(message.body.startsWith("Sam, there's more to black holes"))
        assertTrue(message.prompt.contains("black holes"))

        val anonymous = CheckInComposer.compose(topic("Tell me about black holes"), assistant, "", 0)
        assertTrue(anonymous.body.startsWith("There's more"))
    }

    @Test
    fun wordingRotates() {
        val assistant = BuiltInPersonas.byId("assistant")!!
        val t = topic("Tell me about black holes")
        val titles = (0..2).map { CheckInComposer.compose(t, assistant, null, it).title }.toSet()
        assertEquals(3, titles.size)
    }

    @Test
    fun noTopicFallsBackToThePersonasVoice() {
        val therapist = BuiltInPersonas.byId("therapist")!!
        val caring = CheckInComposer.compose(null, therapist, null, 0)
        assertTrue(caring.title.startsWith(therapist.emoji))
        assertTrue(caring.body.contains("feeling"))

        val assistant = BuiltInPersonas.byId("assistant")!!
        val general = CheckInComposer.compose(null, assistant, null, 0)
        assertEquals(assistant.starters.first(), general.prompt)
    }

    @Test
    fun longTopicsAreShortenedInTheTitle() {
        val t = Topic("the history of the byzantine empire and its art", "the history of the byzantine empire and its art", Topic.Kind.Subject)
        val message = CheckInComposer.compose(t, BuiltInPersonas.byId("assistant")!!, null, 0)
        assertTrue(message.title.contains("…"))
        assertTrue(message.body.contains("its art"))
    }

    private val zone = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        Calendar.getInstance(zone, Locale.US).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, minute) }.timeInMillis

    @Test
    fun checkInsFollowTheUsualTime() {
        val leftAt = at(20, 19, 15)
        assertEquals(at(21, 19, 15), CheckInSchedule.triggerAt(leftAt, 0, 19 * 60 + 15, zone))
        assertEquals(at(23, 19, 15), CheckInSchedule.triggerAt(leftAt, 1, 19 * 60 + 15, zone))
        assertEquals(at(27, 19, 15), CheckInSchedule.triggerAt(leftAt, 2, 19 * 60 + 15, zone))
        assertNull(CheckInSchedule.triggerAt(leftAt, 3, 19 * 60 + 15, zone))
    }

    @Test
    fun quietHoursAreRespected() {
        val leftAt = at(20, 23, 40)
        // Late-night users hear from us in the evening instead.
        assertEquals(at(21, 20, 30), CheckInSchedule.triggerAt(leftAt, 0, 23 * 60 + 40, zone))
        // Early birds a little after quiet hours end.
        assertEquals(at(21, 9, 0), CheckInSchedule.triggerAt(at(20, 6, 0), 0, 6 * 60, zone))
    }

    @Test
    fun habitsBeatOneOffVisits() {
        val evenings = listOf(20 * 60 + 10, 20 * 60 + 30, 13 * 60, 20 * 60 + 50)
        assertEquals(20 * 60 + 30, CheckInSchedule.habitualMinute(evenings, lastOpen = 13 * 60))
        // Not enough history yet: when they last used the app.
        assertEquals(13 * 60, CheckInSchedule.habitualMinute(listOf(20 * 60, 13 * 60), lastOpen = 13 * 60))
        val scattered = listOf(8 * 60 + 40, 12 * 60, 15 * 60, 18 * 60)
        assertEquals(18 * 60, CheckInSchedule.habitualMinute(scattered, lastOpen = 18 * 60))
    }

    @Test
    fun theFirstCheckInIsNeverTooSoon() {
        val trigger = CheckInSchedule.triggerAt(at(20, 23, 0), 0, 9 * 60, zone)
        assertNotNull(trigger)
        assertFalse(trigger!! - at(20, 23, 0) < 10 * 60 * 60_000L)
    }
}
