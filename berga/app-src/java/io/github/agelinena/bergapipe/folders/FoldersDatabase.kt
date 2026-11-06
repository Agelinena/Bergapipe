package io.github.agelinena.bergapipe.folders

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/*
 * Banco das pastas do Bergapipe, separado do banco do PipePipe (newpipe.db). Assim as
 * migrações de banco do criador nunca conflitam com as nossas.
 */

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/** Um vídeo dentro de uma pasta. Guarda os dados para mostrar a lista sem internet. */
@Entity(
    tableName = "folder_videos",
    primaryKeys = ["folder_id", "service_id", "url"],
    indices = [Index("folder_id"), Index(value = ["service_id", "url"])],
)
data class FolderVideoEntity(
    @ColumnInfo(name = "folder_id") val folderId: Long,
    @ColumnInfo(name = "service_id") val serviceId: Int,
    val url: String,
    val title: String,
    @ColumnInfo(name = "stream_type") val streamType: String,
    val duration: Long,
    val uploader: String,
    @ColumnInfo(name = "uploader_url") val uploaderUrl: String?,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
) {
    fun toStreamInfoItem(): StreamInfoItem {
        val type = runCatching { StreamType.valueOf(streamType) }.getOrDefault(StreamType.VIDEO_STREAM)
        return StreamEntity(
            serviceId = serviceId, url = url, title = title, streamType = type,
            duration = duration, uploader = uploader, uploaderUrl = uploaderUrl,
            thumbnailUrl = thumbnailUrl,
        ).toStreamInfoItem()
    }

    companion object {
        fun from(folderId: Long, stream: StreamEntity) = FolderVideoEntity(
            folderId = folderId,
            serviceId = stream.serviceId,
            url = stream.url,
            title = stream.title,
            streamType = stream.streamType.name,
            duration = stream.duration,
            uploader = stream.uploader,
            uploaderUrl = stream.uploaderUrl,
            thumbnailUrl = stream.thumbnailUrl,
        )
    }
}

/** Linha da lista de pastas. */
data class FolderSummary(
    val id: Long,
    val name: String,
    val count: Int,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
)

@Dao
interface FoldersDao {
    @Query(
        """
        SELECT f.id, f.name,
            (SELECT COUNT(*) FROM folder_videos v WHERE v.folder_id = f.id) AS count,
            (SELECT v.thumbnail_url FROM folder_videos v WHERE v.folder_id = f.id
                ORDER BY v.added_at DESC LIMIT 1) AS thumbnail_url
        FROM folders f
        ORDER BY f.name COLLATE NOCASE
        """
    )
    fun summaries(): Flow<List<FolderSummary>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun folders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun folder(id: Long): Flow<FolderEntity?>

    @Query("SELECT * FROM folder_videos WHERE folder_id = :folderId ORDER BY added_at DESC")
    fun videos(folderId: Long): Flow<List<FolderVideoEntity>>

    @Query("SELECT folder_id FROM folder_videos WHERE service_id = :serviceId AND url = :url")
    suspend fun foldersContaining(serviceId: Int, url: String): List<Long>

    @Insert
    suspend fun insertFolder(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderRow(id: Long)

    @Query("DELETE FROM folder_videos WHERE folder_id = :folderId")
    suspend fun deleteFolderVideos(folderId: Long)

    /** Apaga a pasta e os vídeos dela (os vídeos continuam em outras pastas). */
    @Transaction
    suspend fun deleteFolder(id: Long) {
        deleteFolderVideos(id)
        deleteFolderRow(id)
    }

    /** Se o vídeo já estiver na pasta, só atualiza os dados e a data. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addVideo(video: FolderVideoEntity)

    @Query("DELETE FROM folder_videos WHERE folder_id = :folderId AND service_id = :serviceId AND url = :url")
    suspend fun removeVideo(folderId: Long, serviceId: Int, url: String)
}

@Database(
    entities = [FolderEntity::class, FolderVideoEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FoldersDatabase : RoomDatabase() {
    abstract fun dao(): FoldersDao

    companion object {
        const val FILE_NAME = "berga_pastas.db"

        @Volatile
        private var instance: FoldersDatabase? = null

        @JvmStatic
        fun get(context: Context): FoldersDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, FoldersDatabase::class.java, FILE_NAME
                ).build().also { instance = it }
            }
    }
}
