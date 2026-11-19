package ai.impact

import ai.impact.data.skills.BuiltInSkills
import ai.impact.data.skills.PhoneNumbers
import ai.impact.data.skills.ShareTarget
import ai.impact.data.skills.SkillTag
import ai.impact.data.skills.TimeParser
import ai.impact.data.skills.Wikipedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SkillsTest {

    private val zone = TimeZone.getTimeZone("UTC")

    /** Wednesday 19 Nov 2025, 15:00 UTC. */
    private val now = calendar(2025, Calendar.NOVEMBER, 19, 15, 0)

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun parse(text: String) = TimeParser.parse(text, now, zone)

    @Test
    fun relativeTimes() {
        assertEquals(now + 20 * 60_000L, parse("stretch in 20 minutes")!!.triggerAt)
        assertEquals(now + 2 * 3_600_000L, parse("in two hours check the oven")!!.triggerAt)
        assertEquals(now + 30 * 60_000L, parse("in half an hour call Sam")!!.triggerAt)
        assertEquals(now + 86_400_000L, parse("water plants in a day")!!.triggerAt)
    }

    @Test
    fun clockTimes() {
        assertEquals(calendar(2025, Calendar.NOVEMBER, 19, 18, 0), parse("call mom at 6pm")!!.triggerAt)
        assertEquals(calendar(2025, Calendar.NOVEMBER, 19, 17, 30), parse("meeting at 5:30")!!.triggerAt)
        // 9am has already passed today, so it means tomorrow.
        assertEquals(calendar(2025, Calendar.NOVEMBER, 20, 9, 0), parse("standup at 9am")!!.triggerAt)
        // "at 4" said at 3pm means 4pm, not 4am tomorrow.
        assertEquals(calendar(2025, Calendar.NOVEMBER, 19, 16, 0), parse("pick up kids at 4")!!.triggerAt)
        assertEquals(calendar(2025, Calendar.NOVEMBER, 19, 22, 0), parse("take pills at 10 tonight")!!.triggerAt)
    }

    @Test
    fun days() {
        assertEquals(calendar(2025, Calendar.NOVEMBER, 20, 18, 0), parse("call mom tomorrow at 6pm")!!.triggerAt)
        assertEquals(calendar(2025, Calendar.NOVEMBER, 20, 9, 0), parse("tomorrow morning gym")!!.triggerAt)
        assertEquals(calendar(2025, Calendar.NOVEMBER, 20, 9, 0), parse("pay rent tomorrow")!!.triggerAt)
        assertEquals(calendar(2025, Calendar.NOVEMBER, 21, 12, 0), parse("lunch day after tomorrow at noon")!!.triggerAt)
        // Next Monday from a Wednesday.
        assertEquals(calendar(2025, Calendar.NOVEMBER, 24, 10, 0), parse("dentist on monday at 10am")!!.triggerAt)
        // Today's weekday means a week from now.
        assertEquals(calendar(2025, Calendar.NOVEMBER, 26, 9, 0), parse("review on wednesday")!!.triggerAt)
        assertEquals(calendar(2025, Calendar.NOVEMBER, 19, 21, 0), parse("read tonight")!!.triggerAt)
    }

    @Test
    fun titlesDropTheTimeAndLeadIn() {
        assertEquals("Call mom", parse("remind me to call mom tomorrow at 6pm")!!.title)
        assertEquals("Stretch", parse("Stretch in 20 minutes")!!.title)
        assertEquals("Take out the trash", parse("notify me to take out the trash at 8pm")!!.title)
        assertEquals("Reminder", parse("in 5 minutes")!!.title)
    }

    @Test
    fun noTimeMeansNull() {
        assertNull(parse("call mom"))
        assertNull(parse("buy 3 apples"))
    }

    @Test
    fun skillTagRoundTrips() {
        val stored = SkillTag.encode("wikipedia", "Ada Lovelace\nhttps://en.wikipedia.org/wiki/Ada_Lovelace", "She was a mathematician.")
        val decoded = SkillTag.decode(stored)
        assertEquals("wikipedia", decoded.skillId)
        assertEquals("Ada Lovelace\nhttps://en.wikipedia.org/wiki/Ada_Lovelace", decoded.arg)
        assertEquals("She was a mathematician.", decoded.text)

        val noArg = SkillTag.decode(SkillTag.encode("qr_code", null, "https://example.com"))
        assertEquals("qr_code", noArg.skillId)
        assertNull(noArg.arg)
        assertEquals("https://example.com", noArg.text)
    }

    @Test
    fun plainTextIsUntouched() {
        assertEquals("hello", SkillTag.encode(null, null, "hello"))
        val decoded = SkillTag.decode("[[not a tag]] hello")
        assertNull(decoded.skillId)
        assertEquals("[[not a tag]] hello", decoded.text)
    }

    @Test
    fun builtInSkillsAreConsistent() {
        val ids = BuiltInSkills.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue("Bad id $it", Regex("[a-z0-9_]+").matches(it)) }
        // Sharing skills work without a model (the typed text is sent as is).
        BuiltInSkills.all.filter { it.share != null }.forEach { assertTrue(!it.needsModel && !it.isTool) }
        assertTrue(BuiltInSkills.defaultEnabledIds.all { it in ids })
    }

    @Test
    fun findsPhoneNumbersForWhatsApp() {
        assertEquals("15550102030", PhoneNumbers.find("Tell Sam I'm late, +1 555 010 2030"))
        assertEquals("919876543210", PhoneNumbers.find("message +91-98765-43210 happy birthday"))
        assertEquals("4155550123", PhoneNumbers.find("text (415) 555-0123 about dinner"))
        // Times, dates and short numbers aren't phone numbers.
        assertNull(PhoneNumbers.find("meet at 10:30 on 12/05, bring 3 friends"))
        assertNull(PhoneNumbers.find("just say hi"))
    }

    @Test
    fun shareLinksCarryTheText() {
        assertEquals("https://wa.me/15550102030?text=Running%20late%20%F0%9F%99%8F",
            ShareTarget.WhatsApp.webUrl("Running late 🙏", "15550102030"))
        assertEquals("https://wa.me/?text=Hi%20%26%20bye", ShareTarget.WhatsApp.webUrl("Hi & bye", null))
        assertEquals("https://x.com/intent/post?text=Hello%20world", ShareTarget.X.webUrl("Hello world", null))
        assertNull(ShareTarget.Instagram.webUrl("caption", null))
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), ShareTarget.WhatsApp.packages)
    }

    @Test
    fun wikipediaSearchTerms() {
        assertEquals("Ada Lovelace", Wikipedia.searchTerms("Who was Ada Lovelace?"))
        assertEquals("photosynthesis", Wikipedia.searchTerms("what is photosynthesis"))
        assertEquals("Eiffel Tower", Wikipedia.searchTerms("tell me about the Eiffel Tower"))
        assertEquals("Mount Everest", Wikipedia.searchTerms("Mount Everest"))
        assertEquals("Eiffel Tower", Wikipedia.searchTerms("look up the Eiffel Tower on Wikipedia"))
        assertEquals("black holes", Wikipedia.searchTerms("search wikipedia for black holes"))
    }

    @Test
    fun wikipediaParsesFirstPage() {
        val json = """
            {"batchcomplete":true,"query":{"pages":[{"pageid":974,"ns":0,"title":"Ada Lovelace","index":1,
            "extract":"Augusta Ada King, Countess of Lovelace was an English mathematician.",
            "fullurl":"https://en.wikipedia.org/wiki/Ada_Lovelace"}]}}
        """.trimIndent()
        val article = Wikipedia.parse(json)
        assertNotNull(article)
        assertEquals("Ada Lovelace", article!!.title)
        assertEquals("https://en.wikipedia.org/wiki/Ada_Lovelace", article.url)
        assertTrue(article.extract.startsWith("Augusta Ada King"))

        assertNull(Wikipedia.parse("""{"batchcomplete":true}"""))
    }
}
