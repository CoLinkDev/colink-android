package com.colink.android.data.remote.api

import com.colink.android.data.remote.dto.ApiEnvelope
import com.colink.android.data.remote.dto.DeviceListResponseDto
import com.colink.android.data.remote.dto.DeviceKeyUpdateRequestDto
import com.colink.android.data.remote.dto.DeviceNameUpdateRequestDto
import com.colink.android.data.remote.dto.DeviceRegisterRequestDto
import com.colink.android.data.remote.dto.DeviceRegisterResponseDto
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

interface DeviceApi {
    @POST
    suspend fun registerDevice(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: DeviceRegisterRequestDto,
    ): ApiEnvelope<DeviceRegisterResponseDto>

    @GET
    suspend fun listDevices(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<DeviceListResponseDto>

    @PUT
    suspend fun updateDeviceName(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: DeviceNameUpdateRequestDto,
    ): ApiEnvelope<JsonElement>

    @PUT
    suspend fun updateDeviceKey(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: DeviceKeyUpdateRequestDto,
    ): ApiEnvelope<JsonElement>

    @DELETE
    suspend fun deleteDevice(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<JsonElement>

}
