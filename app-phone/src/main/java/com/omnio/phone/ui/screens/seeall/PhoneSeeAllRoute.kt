package com.omnio.phone.ui.screens.seeall

import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.omnio.tv.domain.model.CatalogRow
import java.net.URLEncoder

object PhoneSeeAllRoute {

    const val KEY_ADDON_BASE_URL = "addonBaseUrl"
    const val KEY_ADDON_ID = "addonId"
    const val KEY_ADDON_NAME = "addonName"
    const val KEY_TYPE = "type"
    const val KEY_CATALOG_ID = "catalogId"
    const val KEY_CATALOG_NAME = "catalogName"

    const val ROUTE: String = "seeall" +
        "?$KEY_ADDON_BASE_URL={$KEY_ADDON_BASE_URL}" +
        "&$KEY_ADDON_ID={$KEY_ADDON_ID}" +
        "&$KEY_ADDON_NAME={$KEY_ADDON_NAME}" +
        "&$KEY_TYPE={$KEY_TYPE}" +
        "&$KEY_CATALOG_ID={$KEY_CATALOG_ID}" +
        "&$KEY_CATALOG_NAME={$KEY_CATALOG_NAME}"

    fun navArguments() = listOf(
        navArgument(KEY_ADDON_BASE_URL) { type = NavType.StringType; defaultValue = "" },
        navArgument(KEY_ADDON_ID) { type = NavType.StringType; defaultValue = "" },
        navArgument(KEY_ADDON_NAME) { type = NavType.StringType; defaultValue = "" },
        navArgument(KEY_TYPE) { type = NavType.StringType; defaultValue = "" },
        navArgument(KEY_CATALOG_ID) { type = NavType.StringType; defaultValue = "" },
        navArgument(KEY_CATALOG_NAME) { type = NavType.StringType; defaultValue = "" }
    )

    fun create(row: CatalogRow): String =
        "seeall" +
            "?$KEY_ADDON_BASE_URL=${enc(row.addonBaseUrl)}" +
            "&$KEY_ADDON_ID=${enc(row.addonId)}" +
            "&$KEY_ADDON_NAME=${enc(row.addonName)}" +
            "&$KEY_TYPE=${enc(row.apiType)}" +
            "&$KEY_CATALOG_ID=${enc(row.catalogId)}" +
            "&$KEY_CATALOG_NAME=${enc(row.catalogName)}"

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
