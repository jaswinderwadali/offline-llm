package ai.impact.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Stores conversations and their messages. Independent from the user-memory database. */
@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 2, exportSchema = true)
abstract class ChatHistoryDatabase : RoomDatabase() {
    abstract fun dao(): ChatHistoryDao

    companion object {
        /** Version 2 lets assistant messages carry a generated image. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageWidth INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageHeight INTEGER")
            }
        }

        fun create(context: Context): ChatHistoryDatabase =
            Room.databaseBuilder(context.applicationContext, ChatHistoryDatabase::class.java, "chat_history.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/** A message as stored in history. */
data class StoredMessage(
    val isUser: Boolean,
    val text: String,
    val createdAt: Long,
    val thinkingMillis: Long?,
    val imageId: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null
)

class ChatHistoryRepository(private val dao: ChatHistoryDao) {

    val conversations: Flow<List<ConversationSummary>> = dao.observeSummaries()

    suspend fun createConversation(personaId: String, firstUserMessage: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(
            ConversationEntity(personaId = personaId, title = titleFrom(firstUserMessage), createdAt = now, updatedAt = now)
        )
    }

    suspend fun addMessage(conversationId: Long, message: StoredMessage) {
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                isUser = message.isUser,
                text = message.text,
                createdAt = message.createdAt,
                thinkingMillis = message.thinkingMillis,
                imageId = message.imageId,
                imageWidth = message.imageWidth,
                imageHeight = message.imageHeight
            )
        )
        dao.touch(conversationId, System.currentTimeMillis())
    }

    suspend fun conversation(id: Long): ConversationEntity? = dao.getConversation(id)

    suspend fun latestConversation(personaId: String): ConversationEntity? = dao.latestForPersona(personaId)

    suspend fun messages(conversationId: Long): List<StoredMessage> =
        dao.getMessages(conversationId).map {
            StoredMessage(it.isUser, it.text, it.createdAt, it.thinkingMillis, it.imageId, it.imageWidth, it.imageHeight)
        }

    suspend fun delete(conversationId: Long) = dao.deleteConversation(conversationId)

    suspend fun deleteForPersona(personaId: String) = dao.deleteForPersona(personaId)

    suspend fun deleteAll() = dao.deleteAll()

    private fun titleFrom(text: String): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= 60) oneLine else oneLine.take(57).trimEnd() + "…"
    }
}
