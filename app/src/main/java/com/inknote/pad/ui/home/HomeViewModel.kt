package com.inknote.pad.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknote.pad.data.InkRepositoryProvider
import com.inknote.pad.domain.NoteSummary
import com.inknote.pad.domain.PaperSpec
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 首页状态。
 *
 * Step 4 之前这里的动作都是同步的（内存实现）。
 * 换成 Room 之后它们变成了 suspend —— 数据库不能在主线程查，
 * 所以统一用 viewModelScope 启动。ViewModel 被销毁时这些协程会自动取消，
 * 这正是新建笔记到一半时用户按返回应该有的行为。
 */
class HomeViewModel : ViewModel() {

    private val repository = InkRepositoryProvider.repository

    val notes: StateFlow<List<NoteSummary>> = repository.notes

    /**
     * 新建笔记。纸型在这里被永久定下来。
     *
     * 因为要等数据库插入拿到自增 id 才能导航，所以结果通过回调交出去，
     * 而不是直接返回。
     */
    fun createNote(paper: PaperSpec, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val note = repository.createNote(paper)
            onCreated(note.id)
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { repository.deleteNote(id) }
    }

    fun renameNote(id: Long, title: String) {
        viewModelScope.launch { repository.renameNote(id, title) }
    }
}
