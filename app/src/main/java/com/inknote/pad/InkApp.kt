package com.inknote.pad

import android.app.Application
import com.inknote.pad.data.AppPreferences
import com.inknote.pad.data.AppPreferencesProvider
import com.inknote.pad.data.InkRepository
import com.inknote.pad.data.InkRepositoryProvider
import com.inknote.pad.data.RoomInkRepository

/**
 * 应用入口。
 *
 * 它是「仓库是进程级单例」这件事的唯一归属点 ——
 * 而不是散落在各个 ViewModel 里去 new。
 * 需要在这里做而不是别处，是因为实现要用 Context 拿 filesDir、开 Room 数据库，
 * 而 Context 只有 Application 有。
 */
class InkApp : Application() {

    /**
     * by lazy：数据库和仓库都不在 onCreate 里立刻构造。
     * 冷启动时用户可能只是想看一眼列表，没必要在 Application.onCreate
     * 这个关键路径上把 Room 的 builder 跑一遍。
     */
    val repository: InkRepository by lazy { RoomInkRepository(this) }

    /**
     * 全局设置。它比仓库轻得多（只是 SharedPreferences），
     * 但仍然走 lazy：Application.onCreate 是关键路径，能少做就少做。
     */
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        InkRepositoryProvider.init(repository)
        AppPreferencesProvider.init(preferences)
    }
}
