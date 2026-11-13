package com.pennywiseai.tracker.data.statement.gmail

import android.content.Intent
import android.content.IntentSender

/**
 * Read-only Gmail access for fetching statement PDFs. The standard flavor
 * signs in through Google Play services; F-Droid has no Google sign-in, so
 * its stub reports [isSupported] false and the UI hides the feature.
 */
interface GmailAuthorizer {
    val isSupported: Boolean

    /** A token straight away when access was granted before, else the consent screen to show. */
    suspend fun authorize(): GmailAuthorization

    /** The token from the consent screen's result; null when the user declined. */
    fun tokenFromConsent(data: Intent?): String?

    /** Drops a token Gmail rejected, so the next [authorize] fetches a fresh one. */
    suspend fun invalidate(token: String)
}

sealed interface GmailAuthorization {
    data class Granted(val accessToken: String) : GmailAuthorization
    data class NeedsConsent(val intentSender: IntentSender) : GmailAuthorization
    data object Failed : GmailAuthorization
}

const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
