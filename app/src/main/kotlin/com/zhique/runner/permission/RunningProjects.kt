package com.zhique.runner.permission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 运行中项目登记（进程内真值；权限中心「运行中提醒条」的依据）。 */
object RunningProjects {

    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    val ids: StateFlow<Set<String>> = _ids

    fun enter(projectId: String) {
        _ids.value = _ids.value + projectId
    }

    fun exit(projectId: String) {
        _ids.value = _ids.value - projectId
    }

    fun isRunning(projectId: String): Boolean = projectId in _ids.value
}
