package com.arctechnology.zerofomo.data.inbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** The inbox receiver (inbox/server.py): one POST per forwarded post. */
interface InboxApi {
    @POST("submit")
    suspend fun submit(
        @Body body: SubmissionDto,
        @Header("X-Inbox-Token") token: String,
    ): InboxResponseDto
}

@Serializable
data class SubmissionDto(
    val market: String,
    val kind: String,
    val device: String,
    val text: String = "",
    val url: String = "",
    @SerialName("source_hint") val sourceHint: String = "",
    @SerialName("image_base64") val imageBase64: String? = null,
    @SerialName("image_type") val imageType: String? = null,
    @SerialName("app_version") val appVersion: String = "",
)

@Serializable
data class InboxResponseDto(
    val ok: Boolean = false,
    val id: String? = null,
    val status: String? = null,
    val error: String? = null,
)
