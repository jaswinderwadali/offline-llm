package ai.impact.data.documents

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Where a document is in being read and indexed. */
object DocumentStatus {
    const val INDEXING = "indexing"
    const val READY = "ready"
    const val FAILED = "failed"
}

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** The picked file, so indexing can resume after the app is closed mid-way. */
    val uri: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val status: String,
    val error: String? = null,
    val pageCount: Int? = null,
    val chunkCount: Int = 0,
    val addedAt: Long
)

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(DocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    /** Position in the document, for reading passages back in order. */
    val ordinal: Int,
    val heading: String?,
    val text: String,
    val pageStart: Int?,
    val pageEnd: Int?,
    /** The search model's vector, as little-endian floats (384 for the suggested models). */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray
)

/** What re-scoring a passage with another search model needs. */
data class ChunkText(val id: Long, val heading: String?, val text: String) {
    val embeddingText: String get() = if (heading.isNullOrBlank()) text else "$heading\n$text"
}

/** Just what a search needs to rank every passage. */
data class ChunkVector(val id: Long, val documentId: Long, val embedding: ByteArray)

data class ChunkWithDocument(
    val id: Long,
    val documentId: Long,
    val documentName: String,
    val heading: String?,
    val text: String,
    val pageStart: Int?,
    val pageEnd: Int?
)

@Dao
interface DocumentsDao {
    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE status = :status")
    suspend fun withStatus(status: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: Long): DocumentEntity?

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("UPDATE documents SET status = :status, error = :error, pageCount = :pageCount, chunkCount = :chunkCount WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?, pageCount: Int?, chunkCount: Int)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    @Insert
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("DELETE FROM chunks WHERE documentId = :documentId")
    suspend fun deleteChunks(documentId: Long)

    @Query("SELECT id, heading, text FROM chunks WHERE documentId = :documentId ORDER BY ordinal")
    suspend fun chunkTexts(documentId: Long): List<ChunkText>

    @Query("UPDATE chunks SET embedding = :embedding WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: ByteArray)

    @Query("SELECT c.id, c.documentId, c.embedding FROM chunks c JOIN documents d ON d.id = c.documentId WHERE d.status = 'ready'")
    suspend fun readyVectors(): List<ChunkVector>

    @Query(
        """
        SELECT c.id, c.documentId, d.name AS documentName, c.heading, c.text, c.pageStart, c.pageEnd
        FROM chunks c JOIN documents d ON d.id = c.documentId
        WHERE c.id IN (:ids)
        """
    )
    suspend fun chunks(ids: List<Long>): List<ChunkWithDocument>
}

@Database(entities = [DocumentEntity::class, ChunkEntity::class], version = 1, exportSchema = true)
abstract class DocumentsDatabase : RoomDatabase() {
    abstract fun dao(): DocumentsDao

    companion object {
        fun create(context: Context): DocumentsDatabase =
            Room.databaseBuilder(context.applicationContext, DocumentsDatabase::class.java, "documents.db").build()
    }
}
