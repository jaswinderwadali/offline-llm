package ai.impact

import ai.impact.data.skills.BuiltInSkills
import ai.impact.data.skills.SkillRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SkillRouterTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val now = Calendar.getInstance(zone, Locale.US).apply { clear(); set(2025, Calendar.NOVEMBER, 19, 15, 0) }.timeInMillis
    private val all = BuiltInSkills.all.map { it.id }.toSet()

    private fun route(text: String, enabled: Set<String> = all) = SkillRouter.detect(text, enabled, now, zone)?.id

    @Test
    fun clearRequestsPickTheirSkill() {
        assertEquals("qr_code", route("Make a QR code for https://example.com"))
        assertEquals("qr_code", route("qr for my wifi password"))
        assertEquals("whatsapp", route("Send a WhatsApp to mom saying I'll be late"))
        assertEquals("whatsapp", route("draft a whatsapp message for Sam"))
        assertEquals("instagram", route("Write an Instagram caption for my beach photo"))
        assertEquals("x_post", route("tweet about our launch today"))
        assertEquals("x_post", route("write a post for X about AI on phones"))
        assertEquals("linkedin", route("Draft a LinkedIn post about my new job"))
        assertEquals("text_spinner", route("Rephrase this: we regret to inform you"))
        assertEquals("learn", route("teach me something about black holes"))
    }

    @Test
    fun imageRequestsArePainted() {
        assertEquals("image", route("draw a cat in space"))
        assertEquals("image", route("Create a picture of a sunset over mountains"))
        assertEquals("image", route("can you generate an image of a red fox in the snow?"))
        assertEquals("image", route("make me a wallpaper with neon city lights"))
        assertEquals("image", route("design a logo for my bakery"))
        // Talking about pictures isn't asking for one.
        assertNull(route("how do I make a photo smaller?"))
        assertNull(route("I took a great picture yesterday"))
        assertNull(route("write an Instagram caption for my beach photo", enabled = setOf("image")))
        assertNull(route("draw a cat in space", enabled = setOf("qr_code")))
    }

    @Test
    fun imagePromptIsTheSubject() {
        assertEquals("a cat in space", SkillRouter.imagePrompt("draw a cat in space"))
        assertEquals("a sunset over mountains", SkillRouter.imagePrompt("Create a picture of a sunset over mountains."))
        assertEquals("a red fox in the snow", SkillRouter.imagePrompt("can you generate an image of a red fox in the snow?"))
        assertEquals("logo for my bakery", SkillRouter.imagePrompt("design a logo for my bakery please"))
        assertEquals("portrait of my dog as a king", SkillRouter.imagePrompt("paint a portrait of my dog as a king"))
    }

    @Test
    fun remindersNeedATime() {
        assertEquals("notify", route("remind me to stretch in 20 minutes"))
        assertEquals("notify", route("ping me at 6pm to call mom"))
        assertEquals("reminder", route("add a dentist appointment to my calendar tomorrow at 10am"))
        // Without a time it's just conversation.
        assertNull(route("remind me why I started this"))
    }

    @Test
    fun ordinaryChatStaysChat() {
        assertNull(route("How do QR codes work?"))
        assertNull(route("tell me how whatsapp works"))
        assertNull(route("I'm bored, entertain me"))
        assertNull(route("what is love?"))
        assertNull(route("Can I vent for a minute?"))
        assertNull(route("I keep procrastinating on my LinkedIn profile"))
        assertNull(route("/qr code"))
        // Factual questions are answered by the chat model, offline.
        assertNull(route("Who was Ada Lovelace?"))
        assertNull(route("look up the Eiffel Tower"))
    }

    @Test
    fun onlyEnabledSkillsArePicked() {
        assertNull(route("Make a QR code for example.com", enabled = setOf("whatsapp")))
        // A plain "remind me" falls back to the calendar reminder when notifications are off.
        assertEquals("reminder", route("remind me to stretch in 20 minutes", enabled = setOf("reminder")))
        assertNull(route("remind me to stretch in 20 minutes", enabled = emptySet()))
    }

    @Test
    fun extractsWhatToUse() {
        assertEquals("https://example.com/menu", SkillRouter.qrContent("Make a QR code for https://example.com/menu."))
        assertEquals("example.com", SkillRouter.qrContent("qr code for example.com please"))
        assertEquals("my wifi password", SkillRouter.qrContent("generate a qr code for my wifi password"))
        assertEquals("I'll be 10 minutes late", SkillRouter.messageBody("Send a WhatsApp to mom saying I'll be 10 minutes late"))
        assertEquals("Happy birthday!", SkillRouter.messageBody("whatsapp Sam: happy birthday!"))
        assertEquals("hello there", SkillRouter.messageBody("hello there"))
    }
}
