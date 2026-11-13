package com.moneytracker.app.data.statement.gmail

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Gmail read-only access through Google Play services' authorization API. */
@Singleton
class GoogleGmailAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context
) : GmailAuthorizer {

    override val isSupported = true

    private val client get() = Identity.getAuthorizationClient(context)

    override suspend fun authorize(): GmailAuthorization {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
            .build()
        return try {
            val result = client.authorize(request).await()
            val token = result.accessToken
            val consent = result.pendingIntent
            when {
                result.hasResolution() && consent != null -> GmailAuthorization.NeedsConsent(consent.intentSender)
                token != null -> GmailAuthorization.Granted(token)
                else -> GmailAuthorization.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GmailAuthorization.Failed
        }
    }

    override fun tokenFromConsent(data: Intent?): String? = try {
        client.getAuthorizationResultFromIntent(data).accessToken
    } catch (e: Exception) {
        null
    }

    override suspend fun invalidate(token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            // Best effort: an uncleared token just fails once more.
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
