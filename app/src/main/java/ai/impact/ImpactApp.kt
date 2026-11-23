package ai.impact

import android.app.Application
import ai.impact.data.AppPreferences
import ai.impact.data.checkins.CheckIns
import ai.impact.speech.SpeechManager
import ai.impact.data.documents.DocumentLibrary
import ai.impact.data.documents.SearchModelStore
import ai.impact.data.history.ChatHistoryDatabase
import ai.impact.data.history.ChatHistoryRepository
import ai.impact.data.memory.HeuristicMemoryExtractor
import ai.impact.data.memory.MemoryDatabase
import ai.impact.data.memory.MemoryExtractor
import ai.impact.data.memory.MemoryRepository
import ai.impact.data.skills.Reminders
import ai.impact.image.ImageGallery
import ai.impact.image.ImageModelStore
import ai.impact.models.TextModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ImpactApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    /** For work that outlives any one screen, like tracking model downloads. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Chat history and user memory live in separate databases and know nothing about each other.
    val chatHistory: ChatHistoryRepository by lazy { ChatHistoryRepository(ChatHistoryDatabase.create(this).dao()) }
    val memory: MemoryRepository by lazy { MemoryRepository(MemoryDatabase.create(this).dao()) }
    val memoryExtractor: MemoryExtractor = HeuristicMemoryExtractor()

    // Downloaded models and generated images, shared by the chat, image studio and model screens.
    val textModels: TextModelStore by lazy { TextModelStore(this, appScope) }
    val imageModels: ImageModelStore by lazy { ImageModelStore(this, appScope) }
    val gallery: ImageGallery by lazy { ImageGallery(this) }

    /** The search model and the user's documents, searched by chat. */
    val searchModel: SearchModelStore by lazy { SearchModelStore(this, appScope) }
    val documents: DocumentLibrary by lazy { DocumentLibrary(this, appScope, searchModel) }

    /** Listening and speaking: the phone's offline engines or downloaded models. */
    val speech: SpeechManager by lazy { SpeechManager(this, appScope) }

    /** Friendly nudges about what the user was exploring, once they've been away a while. */
    val checkIns: CheckIns by lazy { CheckIns(this) }

    /** Notifications scheduled by the chat's skills. */
    val reminders: Reminders by lazy { Reminders(this) }

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
    }
}
