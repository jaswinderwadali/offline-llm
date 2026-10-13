package ai.impact.data.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index("personaId"), Index("updatedAt")]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val isUser: Boolean,
    val text: String,
    val createdAt: Long,
    val thinkingMillis: Long? = null
)

/** A conversation row for the history list, with its newest message as a preview. */
data class ConversationSummary(
    val id: Long,
    val personaId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(name = "messageCount") val messageCount: Int,
    @ColumnInfo(name = "lastMessage") val lastMessage: String?
)
