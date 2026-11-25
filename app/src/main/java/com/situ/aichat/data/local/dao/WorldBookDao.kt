package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import kotlinx.coroutines.flow.Flow

/** WB7 书架行：书 + 条目数 + 绑定角色数（非实体投影，只作列表展示）。 */
data class WorldBookSummary(
    @Embedded val book: WorldBookEntity,
    val entryCount: Int,
    val boundCount: Int,
)

/**
 * 世界书一族（书 / 条目 / 角色绑定 / 时效状态）的数据访问（契约 `FABLE5_WORLDBOOK_PROPOSAL.md` §4.1）。
 * 激活引擎（WB3）走 [activeBooksForCharacter] + [entriesForBooks] 一次取全；管理 UI（WB7）走 observe 流。
 */
@Dao
interface WorldBookDao {

    // MARK: - 书

    @Upsert
    suspend fun upsertBook(book: WorldBookEntity)

    @Query("DELETE FROM world_books WHERE uuid = :bookUuid")
    suspend fun deleteBook(bookUuid: String)

    @Query("SELECT * FROM world_books WHERE uuid = :bookUuid")
    suspend fun getBook(bookUuid: String): WorldBookEntity?

    @Query("SELECT * FROM world_books ORDER BY updatedAt DESC")
    fun observeAllBooks(): Flow<List<WorldBookEntity>>

    @Query("SELECT * FROM world_books WHERE uuid = :bookUuid")
    fun observeBook(bookUuid: String): Flow<WorldBookEntity?>

    /** WB7 书架：书带条目数 / 绑定角色数（子查询引用三张表，任一变更 Room 都会重发）。 */
    @Query(
        "SELECT b.*, " +
            "(SELECT COUNT(*) FROM world_book_entries e WHERE e.bookUuid = b.uuid) AS entryCount, " +
            "(SELECT COUNT(*) FROM world_book_bindings x WHERE x.bookUuid = b.uuid) AS boundCount " +
            "FROM world_books b ORDER BY b.updatedAt DESC",
    )
    fun observeBookSummaries(): Flow<List<WorldBookSummary>>

    @Query("SELECT * FROM world_books ORDER BY updatedAt DESC")
    suspend fun getAllBooks(): List<WorldBookEntity>

    /** 某角色本轮生效的书 = 未加入世界时启用的（全局书 ∪ 该角色绑定的书）；已入世角色（joinedWorld = 1）彻底隔离不注入世界书（Q3·3A）。 */
    @Query(
        "SELECT * FROM world_books WHERE enabled = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM characters c WHERE c.uuid = :characterUuid AND c.joinedWorld = 1) " +
            "AND (isGlobal = 1 OR uuid IN (SELECT bookUuid FROM world_book_bindings WHERE characterUuid = :characterUuid))",
    )
    suspend fun activeBooksForCharacter(characterUuid: String): List<WorldBookEntity>

    // MARK: - 条目

    @Upsert
    suspend fun upsertEntry(entry: WorldBookEntryEntity)

    @Upsert
    suspend fun upsertEntries(entries: List<WorldBookEntryEntity>)

    @Query("SELECT * FROM world_book_entries WHERE uuid = :entryUuid")
    suspend fun getEntry(entryUuid: String): WorldBookEntryEntity?

    @Query("SELECT * FROM world_book_entries WHERE bookUuid = :bookUuid ORDER BY displayIndex ASC")
    suspend fun entriesForBook(bookUuid: String): List<WorldBookEntryEntity>

    @Query("SELECT * FROM world_book_entries WHERE bookUuid IN (:bookUuids) ORDER BY displayIndex ASC")
    suspend fun entriesForBooks(bookUuids: List<String>): List<WorldBookEntryEntity>

    @Query("SELECT * FROM world_book_entries WHERE bookUuid = :bookUuid ORDER BY displayIndex ASC")
    fun observeEntriesForBook(bookUuid: String): Flow<List<WorldBookEntryEntity>>

    @Query("SELECT COUNT(*) FROM world_book_entries WHERE bookUuid = :bookUuid")
    suspend fun entryCountForBook(bookUuid: String): Int

    /** WB5 向量条目：定向只写嵌入两列（避免整行 @Upsert 与编辑/导入并发互相覆盖，照项目 targeted UPDATE 惯例）。 */
    @Query("UPDATE world_book_entries SET embedding = :embedding, embeddingSignature = :signature WHERE uuid = :entryUuid")
    suspend fun updateEntryEmbedding(entryUuid: String, embedding: ByteArray?, signature: String?)

    /** WB7 条目开关：targeted UPDATE（与 [updateEntryEmbedding] 同理由——不整行回写防并发互踩）。 */
    @Query("UPDATE world_book_entries SET enabled = :enabled WHERE uuid = :entryUuid")
    suspend fun setEntryEnabled(entryUuid: String, enabled: Boolean)

    @Query("DELETE FROM world_book_entries WHERE uuid = :entryUuid")
    suspend fun deleteEntry(entryUuid: String)

    @Query("DELETE FROM world_book_entries WHERE bookUuid = :bookUuid")
    suspend fun deleteEntriesForBook(bookUuid: String)

    // MARK: - 角色绑定（多对多；全局书不走绑定）

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bind(binding: WorldBookBindingEntity)

    @Query("DELETE FROM world_book_bindings WHERE characterUuid = :characterUuid AND bookUuid = :bookUuid")
    suspend fun unbind(characterUuid: String, bookUuid: String)

    @Query("DELETE FROM world_book_bindings WHERE characterUuid = :characterUuid")
    suspend fun unbindAllForCharacter(characterUuid: String)

    @Query("SELECT bookUuid FROM world_book_bindings WHERE characterUuid = :characterUuid ORDER BY createdAt ASC")
    suspend fun boundBookUuids(characterUuid: String): List<String>

    @Query(
        "SELECT * FROM world_books WHERE uuid IN " +
            "(SELECT bookUuid FROM world_book_bindings WHERE characterUuid = :characterUuid) " +
            "ORDER BY updatedAt DESC",
    )
    fun observeBoundBooks(characterUuid: String): Flow<List<WorldBookEntity>>

    @Query("SELECT COUNT(*) FROM world_book_bindings WHERE bookUuid = :bookUuid")
    suspend fun bindingCountForBook(bookUuid: String): Int

    /** WB6b 备份导出：某书绑定的全部角色 uuid（按绑定先后）。 */
    @Query("SELECT characterUuid FROM world_book_bindings WHERE bookUuid = :bookUuid ORDER BY createdAt ASC")
    suspend fun boundCharacterUuids(bookUuid: String): List<String>

    /** WB7 书详情「在用角色」行（响应式）。 */
    @Query("SELECT characterUuid FROM world_book_bindings WHERE bookUuid = :bookUuid ORDER BY createdAt ASC")
    fun observeBoundCharacterUuids(bookUuid: String): Flow<List<String>>

    /** WB7c 角色侧绑定 sheet：按绑定先后的书 uuid（响应式；「主书 = 第一本」只是展示层约定）。 */
    @Query("SELECT bookUuid FROM world_book_bindings WHERE characterUuid = :characterUuid ORDER BY createdAt ASC")
    fun observeBoundBookUuidsForCharacter(characterUuid: String): Flow<List<String>>

    // MARK: - 时效状态（sticky / cooldown·按会话隔离）

    @Upsert
    suspend fun upsertTimedState(state: WorldBookTimedStateEntity)

    @Query("SELECT * FROM world_book_timed_states WHERE conversationUuid = :conversationUuid")
    suspend fun timedStatesForConversation(conversationUuid: String): List<WorldBookTimedStateEntity>

    @Query(
        "DELETE FROM world_book_timed_states WHERE conversationUuid = :conversationUuid " +
            "AND entryUuid = :entryUuid AND effectType = :effectType",
    )
    suspend fun clearTimedState(conversationUuid: String, entryUuid: String, effectType: String)

    @Query("DELETE FROM world_book_timed_states WHERE conversationUuid = :conversationUuid")
    suspend fun clearTimedStatesForConversation(conversationUuid: String)
}
