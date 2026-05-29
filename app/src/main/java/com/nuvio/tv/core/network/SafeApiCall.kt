package com.nuvio.tv.core.network

import android.content.Context
import com.nuvio.tv.R
import retrofit2.Response

suspend fun <T> safeApiCall(
    context: Context,
    apiCall: suspend () -> Response<T>
): NetworkResult<T> {
    return try {
        val response = apiCall()
        if (response.isSuccessful) {
            response.body()?.let {
                NetworkResult.Success(it)
            } ?: NetworkResult.Error(context.getString(R.string.network_error_empty_response_body))
        } else {
            NetworkResult.Error(response.message(), response.code())
        }
    } catch (e: Exception) {
        NetworkResult.Error(e.message ?: context.getString(R.string.network_error_unknown))
    }
}
