package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.flow.Flow

/**
 * 文风档案领域：档案的新增 / 查询 / 编辑 / 删除，以及书籍与档案绑定关系的一致性。
 * 不建数据库外键，跨表更新（删除档案前解绑书籍）统一在这里用事务完成。
 */
class StyleRepository(private val db: AppDatabase) {
    private val styleDao = db.styleProfileDao()
    private val bookDao = db.bookDao()

    fun observeProfiles(): Flow<List<StyleProfile>> = styleDao.observeAll()

    suspend fun allProfiles(): List<StyleProfile> = styleDao.getAll()

    suspend fun getProfile(id: Long): StyleProfile? = styleDao.getById(id)

    /** 按名称查重（保存新档案前调用，避免同名档案被重复写入） */
    suspend fun findByName(name: String): StyleProfile? = styleDao.findByName(name)

    /** 新建档案（传入的 id 会被忽略），返回新档案 id；校验失败抛 [IllegalArgumentException] */
    suspend fun createProfile(profile: StyleProfile): Long {
        val now = System.currentTimeMillis()
        val normalized = StyleProfileRules.normalize(profile)
            .copy(id = 0, createdAt = now, updatedAt = now)
        return styleDao.insert(normalized)
    }

    /** 修改档案（名称 / 分析结果等）；id 不存在时不做任何事 */
    suspend fun updateProfile(profile: StyleProfile) {
        val normalized = StyleProfileRules.normalize(profile)
        if (styleDao.getById(normalized.id) == null) return
        styleDao.update(normalized.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * 删除档案：同一事务内先把引用该档案的书籍全部解绑（styleId = 0），再删除档案。
     * 中途失败整体回滚，不会出现只解绑一部分书籍的状态；不级联删除书籍。
     */
    suspend fun deleteProfile(id: Long) = db.withTransaction {
        bookDao.clearStyle(id)
        styleDao.deleteById(id)
    }

    /** 绑定该档案的书籍数量（基于真实数据库数据） */
    suspend fun boundBookCount(styleId: Long): Int = bookDao.countByStyle(styleId)

    /**
     * 为书籍设置文风绑定；[styleId] 为 0 表示解绑。
     * 绑定不存在的档案时抛 [IllegalArgumentException]，避免写出指向空档案的引用。
     */
    suspend fun bindBook(bookId: Long, styleId: Long) {
        if (styleId == 0L) {
            bookDao.setStyle(bookId, 0)
            return
        }
        requireNotNull(styleDao.getById(styleId)) { "文风档案不存在：$styleId" }
        bookDao.setStyle(bookId, styleId)
    }

    /** 清除书籍的文风绑定 */
    suspend fun unbindBook(bookId: Long) = bookDao.setStyle(bookId, 0)
}
