package ai.impact

import android.app.Application
import ai.impact.data.AppPreferences
import ai.impact.data.history.ChatHistoryDatabase
import ai.impact.data.history.ChatHistoryRepository
import ai.impact.data.memory.HeuristicMemoryExtractor
import ai.impact.data.memory.MemoryDatabase
import ai.impact.data.memory.MemoryExtractor
import ai.impact.data.memory.MemoryRepository

class ImpactApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    // Chat history and user memory live in separate databases and know nothing about each other.
    val chatHistory: ChatHistoryRepository by lazy { ChatHistoryRepository(ChatHistoryDatabase.create(this).dao()) }
    val memory: MemoryRepository by lazy { MemoryRepository(MemoryDatabase.create(this).dao()) }
    val memoryExtractor: MemoryExtractor = HeuristicMemoryExtractor()

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
    }
}
