package com.pennywiseai.tracker.data.statement.gmail

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** A PDF attached to a statement email. */
data class GmailStatement(
    val messageId: String,
    val attachmentId: String,
    val fileName: String,
    val from: String,
    val subject: String,
    val receivedAtMillis: Long
) {
    /** Stable across searches: the same attachment always lands in the same cache file. */
    val key: String get() = "$messageId-${fileName.hashCode().toUInt()}"
}

/** Gmail rejected the token (expired or revoked). */
class GmailAuthExpiredException : Exception("Gmail access token rejected")

/**
 * Finds statement PDFs in the signed-in user's Gmail and downloads them to the
 * app's cache, where the regular PDF import reads them. Only the Gmail REST
 * API is called, with a read-only token; nothing leaves the device.
 */
@Singleton
class GmailStatementClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = HttpClient(Android) {
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** Statement PDFs received since [sinceMillis], newest first. */
    suspend fun findStatements(token: String, sinceMillis: Long): List<GmailStatement> = authenticated {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val page: MessageList = client.get("$BASE/messages") {
                bearerAuth(token)
                parameter("q", searchQuery(sinceMillis))
                parameter("maxResults", 100)
                pageToken?.let { parameter("pageToken", it) }
            }.body()
            ids += page.messages.orEmpty().map { it.id }
            pageToken = page.nextPageToken
        } while (pageToken != null && ids.size < MAX_MESSAGES)

        ids.take(MAX_MESSAGES).flatMap { id ->
            val message: Message = client.get("$BASE/messages/$id") {
                bearerAuth(token)
                parameter("format", "full")
            }.body()
            val headers = message.payload?.headers.orEmpty()
            fun header(name: String) = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()
            message.payload?.pdfParts().orEmpty().filter { isStatementFile(it.filename!!) }.map { part ->
                GmailStatement(
                    messageId = id,
                    attachmentId = part.body!!.attachmentId!!,
                    fileName = part.filename!!,
                    from = header("From"),
                    subject = header("Subject"),
                    receivedAtMillis = message.internalDate?.toLongOrNull() ?: 0L
                )
            }
        }.sortedByDescending { it.receivedAtMillis }
    }

    /** The statement's PDF in the app cache (downloaded once, then reused). */
    suspend fun download(token: String, statement: GmailStatement): File = withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, CACHE_DIR).apply { mkdirs() }, "${statement.key}.pdf")
        if (file.length() > 0) return@withContext file
        val attachment: Attachment = authenticated {
            client.get("$BASE/messages/${statement.messageId}/attachments/${statement.attachmentId}") {
                bearerAuth(token)
            }.body()
        }
        file.writeBytes(Base64.getUrlDecoder().decode(attachment.data))
        file
    }

    private suspend fun <T> authenticated(block: suspend () -> T): T = try {
        block()
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.Unauthorized) throw GmailAuthExpiredException()
        // Google's error body names the cause (API not enabled, missing scope, …).
        val reason = runCatching { e.response.bodyAsText() }.getOrNull()?.take(600)
        Log.w(TAG, "Gmail API ${e.response.status.value}: $reason")
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Gmail request failed: ${e.javaClass.simpleName}: ${e.message}")
        throw e
    }

    private fun Part.pdfParts(): List<Part> {
        val own = if (filename?.endsWith(".pdf", ignoreCase = true) == true && body?.attachmentId != null) listOf(this) else emptyList()
        return own + parts.orEmpty().flatMap { it.pdfParts() }
    }

    @Serializable private data class MessageList(val messages: List<MessageRef>? = null, val nextPageToken: String? = null)
    @Serializable private data class MessageRef(val id: String)
    @Serializable private data class Message(val id: String, val internalDate: String? = null, val payload: Part? = null)
    @Serializable private data class Part(
        val filename: String? = null,
        val headers: List<Header>? = null,
        val body: Body? = null,
        val parts: List<Part>? = null
    )
    @Serializable private data class Header(val name: String, val value: String)
    @Serializable private data class Body(val attachmentId: String? = null)
    @Serializable private data class Attachment(val data: String)

    companion object {
        /** The furthest back a fetch looks: a few statement cycles, no more. */
        const val DEFAULT_MONTHS = 3

        /** How far back a first fetch looks: to January of this year (at least [DEFAULT_MONTHS]), so reward checks have a full history. */
        fun monthsBack(nowMillis: Long): Int =
            maxOf(DEFAULT_MONTHS, java.time.Instant.ofEpochMilli(nowMillis).atZone(java.time.ZoneId.systemDefault()).monthValue)
        private const val MAX_MESSAGES = 400

        /** Senders whose "statements" aren't bank or card statements: brokers, depositories, fund registrars and fund houses. */
        private val EXCLUDED_SENDERS = listOf(
            "zerodha.com", "zerodha.net", "nse_alerts",
            "camsonline.com", "kfintech.com", "karvy.com", "mfcentral.com", "mfuonline.com",
            "nsdl.co.in", "cdslindia.com",
            "groww.in", "upstox.com", "angelone.in", "indmoney.com", "kuvera.in", "paytmmoney.com",
            "hdfcfund.com", "icicipruamc.com", "sbimf.com", "axismf.com", "nipponindiamf.com", "utimf.com", "kotakmf.com"
        )

        /** Subjects of investment statements, whoever sends them. */
        private val EXCLUDED_SUBJECTS = listOf(
            "mutual fund", "folio", "demat", "capital gain", "contract note", "holding statement",
            "portfolio", "consolidated account statement", "factsheet"
        )

        /** Attachments that ride along with a real statement but aren't one: terms, fee schedules, fact sheets. */
        private val NOT_A_STATEMENT_FILE = Regex(
            """key[\s_-]?fact|(?<![a-z])kfs(?![a-z])|mitc|terms|t&c|tnc|brochure|welcome|schedule[\s_-]?of[\s_-]?charges|factsheet|fact[\s_-]?sheet""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The Gmail search for bank and card statements: a PDF received after
         * [sinceMillis] whose *subject* (or file name) says statement — not any mail
         * that mentions one — minus investment senders and subjects.
         */
        internal fun searchQuery(sinceMillis: Long): String =
            "after:${sinceMillis / 1000} has:attachment filename:pdf " +
                "(subject:(statement OR estatement OR e-statement) OR filename:statement) " +
                EXCLUDED_SENDERS.joinToString(" ") { "-from:$it" } + " " +
                EXCLUDED_SUBJECTS.joinToString(" ") { "-subject:\"$it\"" }

        internal fun isStatementFile(fileName: String): Boolean = !NOT_A_STATEMENT_FILE.containsMatchIn(fileName)
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val CACHE_DIR = "gmail-statements"
        private const val TAG = "GmailStatements"
    }
}
