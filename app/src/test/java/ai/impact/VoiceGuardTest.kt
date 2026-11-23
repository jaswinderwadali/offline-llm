package ai.impact

import ai.impact.speech.VoiceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGuardTest {

    @Test
    fun realRequestsPassThrough() {
        assertEquals("What is the emergency radio code?", VoiceGuard.cleanTranscript(" What is the emergency radio code? "))
        assertEquals("Thank you.", VoiceGuard.cleanTranscript("Thank you."))
        assertEquals("Remind me at 6pm to call mom", VoiceGuard.cleanTranscript("Remind me at 6pm to call mom"))
        // Saying something twice is still a request.
        assertEquals("Stop, stop.", VoiceGuard.cleanTranscript("Stop, stop."))
    }

    @Test
    fun noiseAndMadeUpCaptionsAreDropped() {
        // Seen on the device: Whisper looping on a sound.
        assertNull(VoiceGuard.cleanTranscript("Ah, ah, ah, ah, ah, ah, ah, ah, ah, ah, ah"))
        assertNull(VoiceGuard.cleanTranscript("[Music]"))
        assertNull(VoiceGuard.cleanTranscript("(applause) ♪♪"))
        assertNull(VoiceGuard.cleanTranscript("Thanks for watching!"))
        assertNull(VoiceGuard.cleanTranscript("Subtitles by the Amara.org community"))
        assertNull(VoiceGuard.cleanTranscript("   ...  "))
        assertNull(VoiceGuard.cleanTranscript("the the the the the the"))
        assertNull(VoiceGuard.cleanTranscript("I'm going I'm going I'm going I'm going I'm going"))
        // Annotations are removed from real speech.
        assertEquals("Turn the lights off", VoiceGuard.cleanTranscript("[BLANK_AUDIO] Turn the lights off (coughs)"))
    }

    @Test
    fun goodRepliesAreKept() {
        val reply = VoiceGuard.checkReply("The emergency radio code is BLUE-HERON-42.")
        assertEquals(VoiceGuard.Reply.Ok("The emergency radio code is BLUE-HERON-42."), reply)
    }

    @Test
    fun emptyAndCutOffRepliesAreCaught() {
        assertEquals(VoiceGuard.Reply.Empty, VoiceGuard.checkReply(""))
        assertEquals(VoiceGuard.Reply.Empty, VoiceGuard.checkReply("   …  "))
        assertEquals(VoiceGuard.Reply.Empty, VoiceGuard.checkReply("<|im_end|>"))
    }

    @Test
    fun loopingRepliesAreCaught() {
        assertEquals(VoiceGuard.Reply.Garbled, VoiceGuard.checkReply("yes yes yes yes yes yes yes yes"))
        assertEquals(VoiceGuard.Reply.Garbled, VoiceGuard.checkReply("I can help I can help I can help I can help I can help"))
    }

    @Test
    fun repeatedSentencesAreReadOnce() {
        val reply = VoiceGuard.checkReply("I'm here to help. I'm here to help. I'm here to help. What would you like?")
        assertEquals(VoiceGuard.Reply.Ok("I'm here to help. What would you like?"), reply)
    }

    @Test
    fun aReplyThatWritesTheNextTurnIsCut() {
        val reply = VoiceGuard.checkReply("It's sunny today.\nUser: what about tomorrow?\nAssistant: Rain.")
        assertEquals(VoiceGuard.Reply.Ok("It's sunny today."), reply)
        assertEquals(VoiceGuard.Reply.Ok("Hi there."), VoiceGuard.checkReply("Hi there.<|im_end|><|im_start|>"))
    }

    @Test
    fun normalProseIsntALoop() {
        val words = "the cat sat on the mat and the dog sat on the rug while the bird sang".split(" ")
        assertFalse(VoiceGuard.isLoop(words))
        assertTrue(VoiceGuard.isLoop("na na na na na na batman".split(" ")))
    }
}
