package com.ljyh.mei.data.model.melox

data class AccountDevice(
    val key: String,
    val name: String,
    val region: String,
    val lastActiveTime: Long,
    val current: Boolean,
    val os: String,
)

data class AccountAuthorization(val clientId: String, val name: String, val description: String)
data class AccountAuthorizationPage(val records: List<AccountAuthorization>, val cursor: String?, val more: Boolean)
data class AccountBinding(val type: Int, val bound: Boolean, val displayName: String)
data class AccountSecuritySummary(
    val phone: String?,
    val phoneBound: Boolean,
    val protectionEnabled: Boolean?,
    val deviceCount: Int?,
    val bindings: List<AccountBinding>?,
    val errors: List<String>,
)

data class MusicianAccount(
    val musician: Boolean,
    val dj: Boolean,
    val creator: Boolean,
    val live: Boolean,
    val userStatus: Int,
    val certificationStatus: Int,
    val remainingPublications: Int?,
)
