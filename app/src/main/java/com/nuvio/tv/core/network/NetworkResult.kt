package com.nuvio.tv.core.network

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val code: Int? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()

    companion object {
        /**
         * Sentinel error code used by MetaRepository to signal that the requested
         * metadata is already available from the catalog source addon, so no
         * additional network request was made. Callers should treat the existing
         * catalog data as sufficient and mark the item as resolved.
         */
        const val SOURCE_SUFFICIENT_CODE = -9999

        /**
         * Sentinel error code used by MetaRepository when every addon answered and none of them
         * carries the requested item. The lookup did not fail, so callers should treat the result
         * as final and not retry it; a transport failure returns a plain Error instead.
         */
        const val META_NOT_FOUND_CODE = -9998
    }
}
