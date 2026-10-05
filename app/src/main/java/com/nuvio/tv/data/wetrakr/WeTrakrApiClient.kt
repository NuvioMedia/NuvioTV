package com.nuvio.tv.data.wetrakr

class WeTrakrApiClient(
    private val http: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
    private val store: WeTrakrAuthStore
) {
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        scope: WeTrakrAuthScope = store.scope(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse = execute(WeTrakrHttpMethod.GET, path, query, null, scope, acceptedStatuses)

    suspend fun post(
        path: String,
        body: String,
        scope: WeTrakrAuthScope = store.scope(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse = execute(WeTrakrHttpMethod.POST, path, emptyMap(), body, scope, acceptedStatuses)

    suspend fun delete(
        path: String,
        body: String? = null,
        scope: WeTrakrAuthScope = store.scope(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse = execute(WeTrakrHttpMethod.DELETE, path, emptyMap(), body, scope, acceptedStatuses)

    suspend fun refreshUser(scope: WeTrakrAuthScope = store.scope()): WeTrakrUser {
        val previousLimitKey = limitKey(scope)
        val user = decodeWeTrakrUser(get("/account/settings", scope = scope).body)
        if (!store.saveUser(user, scope)) store.checkScope(scope)
        http.associateAccountLimit(previousLimitKey, "user:${user.id}")
        store.checkScope(scope)
        return user
    }

    private suspend fun execute(
        method: WeTrakrHttpMethod,
        path: String,
        query: Map<String, String>,
        body: String?,
        scope: WeTrakrAuthScope,
        acceptedStatuses: Set<Int>
    ): WeTrakrHttpResponse {
        var authorization = auth.authorization(scope)
        repeat(2) { attempt ->
            val response = http.execute(
                WeTrakrHttpRequest(
                    method = method,
                    path = path,
                    query = query,
                    body = body,
                    accessToken = authorization.tokens.accessToken,
                    limitKey = limitKey(scope)
                ),
                checkScope = { store.checkScope(scope) }
            )
            if (response.status == 401) {
                if (attempt == 1) {
                    store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED, authorization.tokens.accessToken)
                    throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
                }
                authorization = auth.authorization(scope, authorization.tokens.accessToken)
            } else {
                if (response.status !in 200..299 && response.status !in acceptedStatuses) {
                    throw WeTrakrApiException(response.status, response.errorCode())
                }
                return response
            }
        }
        throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
    }

    private fun limitKey(scope: WeTrakrAuthScope): String = store.state.value.user?.id?.let { "user:$it" }
        ?: "profile:${scope.profileId}:${scope.generation}"
}
