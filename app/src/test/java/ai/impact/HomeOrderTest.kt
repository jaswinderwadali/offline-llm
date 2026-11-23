package ai.impact

import ai.impact.data.BuiltInPersonas
import ai.impact.data.history.ConversationSummary
import ai.impact.ui.home.byLastUse
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderTest {

    private fun chat(personaId: String, updatedAt: Long) = ConversationSummary(updatedAt, personaId, "t", 0, updatedAt, 2, null)

    @Test
    fun mostRecentlyUsedComesFirst() {
        val ordered = byLastUse(
            BuiltInPersonas.all,
            listOf(chat("tutor", 100), chat("therapist", 300), chat("tutor", 500), chat("friend", 200))
        ).map { it.id }
        // Tutor's newest chat (500) beats therapist (300) and friend (200).
        assertEquals(listOf("tutor", "therapist", "friend"), ordered.take(3))
    }

    @Test
    fun neverUsedKeepTheirUsualOrder() {
        val ordered = byLastUse(BuiltInPersonas.all, listOf(chat("philosopher", 1))).map { it.id }
        assertEquals("philosopher", ordered.first())
        assertEquals(BuiltInPersonas.all.map { it.id }.filter { it != "philosopher" }, ordered.drop(1))
    }

    @Test
    fun noChatsMeansTheUsualOrder() {
        assertEquals(BuiltInPersonas.all, byLastUse(BuiltInPersonas.all, emptyList()))
    }
}
