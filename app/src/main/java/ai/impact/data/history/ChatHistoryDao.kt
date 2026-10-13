package ai.impact.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatHistoryDao {

    @Query(
        """
        SELECT c.id, c.personaId, c.title, c.createdAt, c.updatedAt,
            (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount,
            (SELECT m.text FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastMessage
        FROM conversations c
        ORDER BY c.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE personaId = :personaId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForPersona(personaId: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM conversations WHERE personaId = :personaId")
    suspend fun deleteForPersona(personaId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
