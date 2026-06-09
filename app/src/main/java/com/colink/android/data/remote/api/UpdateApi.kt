package com.colink.android.data.remote.api

import com.colink.android.data.remote.dto.ApiEnvelope
import com.colink.android.data.remote.dto.UpdateCheckResponseDto
import retrofit2.http.GET
import retrofit2.http.Url

interface UpdateApi {
    @GET
    suspend fun checkUpdate(
        @Url url: String,
    ): ApiEnvelope<UpdateCheckResponseDto>
}
