package ai.impact

import ai.impact.data.history.ChatHistoryDatabase
import ai.impact.data.history.ChatHistoryRepository
import ai.impact.data.history.StoredMessage
import ai.impact.data.memory.MemoryCategory
import ai.impact.data.memory.MemoryDatabase
import ai.impact.data.memory.MemoryRepository
import ai.impact.data.memory.MemorySource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {

    private lateinit var historyDb: ChatHistoryDatabase
    private lateinit var memoryDb: MemoryDatabase
    private lateinit var history: ChatHistoryRepository
    private lateinit var memory: MemoryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        historyDb = Room.inMemoryDatabaseBuilder(context, ChatHistoryDatabase::class.java).build()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).build()
        history = ChatHistoryRepository(historyDb.dao())
        memory = MemoryRepository(memoryDb.dao())
    }

    @After
    fun tearDown() {
        historyDb.close()
        memoryDb.close()
    }

    @Test
    fun conversationSummaryShowsCountAndLatestMessage() = runBlocking {
        val id = history.createConversation("therapist", "I've been feeling anxious lately and can't sleep at night at all")
        history.addMessage(id, StoredMessage(true, "I've been feeling anxious", 1, null))
        history.addMessage(id, StoredMessage(false, "<think>hmm</think>That sounds hard.", 2, 900))

        val summary = history.conversations.first().single()
        assertEquals("therapist", summary.personaId)
        assertEquals(2, summary.messageCount)
        assertEquals("<think>hmm</think>That sounds hard.", summary.lastMessage)
        assertTrue("long titles are shortened", summary.title.length <= 60)

        val messages = history.messages(id)
        assertEquals(listOf(true, false), messages.map { it.isUser })
        assertEquals(900L, messages[1].thinkingMillis)
    }

    @Test
    fun latestConversationIsPerPersona() = runBlocking {
        val coach = history.createConversation("life_coach", "Goals")
        Thread.sleep(5)
        history.createConversation("friend", "Hey")
        assertEquals(coach, history.latestConversation("life_coach")?.id)
        assertNull(history.latestConversation("philosopher"))
    }

    @Test
    fun deletingConversationCascadesToMessages() = runBlocking {
        val id = history.createConversation("friend", "Hi")
        history.addMessage(id, StoredMessage(true, "Hi", 1, null))
        history.delete(id)
        assertTrue(history.conversations.first().isEmpty())
        assertTrue(history.messages(id).isEmpty())
    }

    @Test
    fun memoryDeduplicates() = runBlocking {
        assertNotNull(memory.remember("Is a nurse", MemoryCategory.Work, MemorySource.Auto))
        assertNull(memory.remember("is a nurse.", MemoryCategory.Work, MemorySource.Auto))
        assertEquals(1, memory.memories.first().size)
    }

    @Test
    fun historyAndMemoryAreIndependent() = runBlocking {
        val id = history.createConversation("friend", "Hi")
        history.addMessage(id, StoredMessage(true, "I'm a nurse", 1, null))
        memory.remember("Is a nurse", MemoryCategory.Work, MemorySource.Auto)

        history.deleteAll()
        assertEquals("clearing history keeps memory", 1, memory.memories.first().size)

        history.createConversation("friend", "Hi again")
        memory.deleteAll()
        assertEquals("clearing memory keeps history", 1, history.conversations.first().size)
    }
}
