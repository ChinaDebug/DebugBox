package com.tvbox.kotlin.core.data.repo

import com.tvbox.kotlin.core.data.db.AppDataManager
import com.tvbox.kotlin.core.data.entity.SearchHistory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索历史数据提供者，封装关键词查询、新增、清理与去重上限（最多 30 条）。
 */
@Singleton
class SearchPresenter @Inject constructor(
    private val appDataManager: AppDataManager
) {

    private val searchDao get() = appDataManager.getSearchDao()

    suspend fun keywordsExist(keyword: String): Boolean =
        searchDao.getByKeywords(keyword).isNotEmpty()

    suspend fun getSearchHistory(): ArrayList<SearchHistory> =
        ArrayList(searchDao.getAll())

    suspend fun addKeyWordsTodb(keyword: String) {
        val allHistory = searchDao.getAll()
        if (allHistory.size > MAX_HISTORY) {
            searchDao.delete(allHistory.first())
        }
        val searchHistory = SearchHistory(searchKeyWords = keyword)
        searchDao.insert(searchHistory)
    }

    suspend fun clearSearchHistory() {
        searchDao.deleteAll()
    }

    companion object {
        /** 搜索历史上限，超出时移除最早一条 */
        private const val MAX_HISTORY = 29
    }
}
