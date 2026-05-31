package com.colink.android.data.remote.api

import com.colink.android.data.remote.dto.ApiEnvelope
import com.colink.android.data.remote.dto.WsTicketRequestDto
import com.colink.android.data.remote.dto.WsTicketResponseDto
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface WsApi {
    @POST
    suspend fun ticket(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: WsTicketRequestDto,
    ): ApiEnvelope<WsTicketResponseDto>
}
