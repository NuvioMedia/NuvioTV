package com.nuvio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false
) {
    val isPrimary: Boolean get() = id == 1
}
