package ai.impact.data.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class MemoryCategory(val label: String, val emoji: String) {
    Personal("About you", "🙂"),
    Work("Work & study", "💼"),
    Relationships("People & pets", "💞"),
    Preferences("Likes & dislikes", "⭐"),
    Goals("Goals", "🎯"),
    Wellbeing("Health & wellbeing", "🌿"),
    Other("Other", "📝")
}

enum class MemorySource { Auto, Manual }

@Entity(tableName = "memories", indices = [Index("category"), Index("updatedAt")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: MemoryCategory,
    val source: MemorySource,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

class MemoryConverters {
    @TypeConverter fun categoryToString(value: MemoryCategory): String = value.name
    @TypeConverter fun stringToCategory(value: String): MemoryCategory =
        MemoryCategory.entries.firstOrNull { it.name == value } ?: MemoryCategory.Other
    @TypeConverter fun sourceToString(value: MemorySource): String = value.name
    @TypeConverter fun stringToSource(value: String): MemorySource =
        MemorySource.entries.firstOrNull { it.name == value } ?: MemorySource.Manual
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

/** Stores long-term facts about the user. Independent from the chat-history database. */
@Database(entities = [MemoryEntity::class], version = 1, exportSchema = true)
@TypeConverters(MemoryConverters::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        fun create(context: Context): MemoryDatabase =
            Room.databaseBuilder(context.applicationContext, MemoryDatabase::class.java, "user_memory.db").build()
    }
}

class MemoryRepository(private val dao: MemoryDao) {

    companion object {
        private const val CONTEXT_CHAR_BUDGET = 700

        /** Compact bullet list of what's known about the user, sized to fit a small model's context window. */
        fun formatForPrompt(memories: List<MemoryEntity>): String? {
            val lines = mutableListOf<String>()
            var used = 0
            for (memory in memories) {
                val line = "- ${memory.content}"
                if (used + line.length > CONTEXT_CHAR_BUDGET) break
                lines += line
                used += line.length
            }
            return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    val memories: Flow<List<MemoryEntity>> = dao.observeAll()

    /** Saves a memory unless an equivalent one already exists. Returns the saved row, or null if it was a duplicate. */
    suspend fun remember(content: String, category: MemoryCategory, source: MemorySource): MemoryEntity? {
        val clean = content.trim().removeSuffix(".").trim()
        if (clean.length < 4) return null
        val key = clean.normalized()
        if (dao.getAll().any { existing -> existing.content.normalized().let { it == key || it.contains(key) } }) return null
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(content = clean, category = category, source = source, createdAt = now, updatedAt = now)
        return entity.copy(id = dao.insert(entity))
    }

    suspend fun update(memory: MemoryEntity) = dao.update(memory.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    private fun String.normalized() = lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
}
