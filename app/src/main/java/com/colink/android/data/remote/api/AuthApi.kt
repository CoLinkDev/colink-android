package com.colink.android.data.remote.api

import com.colink.android.data.remote.dto.ApiEnvelope
import com.colink.android.data.remote.dto.AuthResponseDto
import com.colink.android.data.remote.dto.LoginRequestDto
import com.colink.android.data.remote.dto.LogoutRequestDto
import com.colink.android.data.remote.dto.RefreshRequestDto
import com.colink.android.data.remote.dto.RefreshResponseDto
import com.colink.android.data.remote.dto.RegisterRequestDto
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface AuthApi {
    @POST
    suspend fun login(
        @Url url: String,
        @Body request: LoginRequestDto,
    ): ApiEnvelope<AuthResponseDto>

    @POST
    suspend fun register(
        @Url url: String,
        @Body request: RegisterRequestDto,
    ): ApiEnvelope<AuthResponseDto>

    @POST
    suspend fun refresh(
        @Url url: String,
        @Body request: RefreshRequestDto,
    ): ApiEnvelope<RefreshResponseDto>

    @POST
    suspend fun logout(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: LogoutRequestDto,
    ): ApiEnvelope<JsonElement>
}
