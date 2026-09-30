package com.zhique.core.project

import kotlinx.serialization.Serializable

@Serializable
data class PermissionRecord(val capability: String, val state: String, val lastAsked: Long = 0)

@Serializable
data class ExportRecord(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val at: Long,
    val variant: String,
)

@Serializable
data class RepoBinding(
    val owner: String,
    val repo: String,
    val branch: String = "main",
    val lastPushedSha: String? = null,
)

@Serializable
data class ProjectMeta(
    val id: String,                   // uuid
    var name: String,
    var group: String = "",           // 文件夹分组
    var runnerMode: String = "drawer",// drawer | split | bubble
    val permissions: MutableMap<String, PermissionRecord> = mutableMapOf(), // capability->record
    val permissionUsage: MutableMap<String, Int> = mutableMapOf(),          // 运行期真实使用计数
    var export: ExportRecord? = null,
    var repo: RepoBinding? = null,
    var providerOverride: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
)
