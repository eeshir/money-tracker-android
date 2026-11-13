package com.pennywiseai.tracker.data.statement.gmail

import android.content.Intent
import javax.inject.Inject

/** F-Droid has no Google Play services, so no Google sign-in: the Gmail fetch is hidden. */
class UnsupportedGmailAuthorizer @Inject constructor() : GmailAuthorizer {
    override val isSupported = false
    override suspend fun authorize(): GmailAuthorization = GmailAuthorization.Failed
    override fun tokenFromConsent(data: Intent?): String? = null
    override suspend fun invalidate(token: String) = Unit
}
