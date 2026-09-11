package com.example.ami.escalation

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

data class EscalationRequest(
    /** The chain to notify, in order. The server mails each one. */
    val caregiverEmails: List<String>,
    val medicineName: String,
    val message: String,
    val timestampIso: String,
    /** True when nobody has answered for several check-ins running. */
    val urgent: Boolean = false
)

data class SummaryRequest(
    val caregiverEmails: List<String>,
    val subject: String,
    val body: String
)

data class CallRequest(
    val phoneNumber: String,
    val medicineName: String,
    val message: String
)

/** `sid` is Twilio's call identifier, used to ask how the call went. */
data class CallResponse(val ok: Boolean = false, val sid: String? = null)

/**
 * Twilio's own call status, relayed by the backend's status webhook.
 * `status` is one of queued/ringing/in-progress/completed/busy/no-answer/failed/canceled.
 */
data class CallStatusResponse(
    val sid: String? = null,
    val status: String? = null,
    val answered: Boolean = false,
    val finished: Boolean = false
)

interface EscalationApi {
    @POST("api/escalate")
    suspend fun escalate(
        @Header("x-ami-shared-secret") secret: String,
        @Body request: EscalationRequest
    ): Response<Unit>

    @POST("api/summary")
    suspend fun summary(
        @Header("x-ami-shared-secret") secret: String,
        @Body request: SummaryRequest
    ): Response<Unit>

    @POST("api/call")
    suspend fun call(
        @Header("x-ami-shared-secret") secret: String,
        @Body request: CallRequest
    ): Response<CallResponse>

    @GET("api/call/{sid}/status")
    suspend fun callStatus(
        @Header("x-ami-shared-secret") secret: String,
        @Path("sid") sid: String
    ): Response<CallStatusResponse>
}
