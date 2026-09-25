package com.colink.android.data.remote.api

import com.colink.android.data.remote.dto.ApiEnvelope
import kotlinx.serialization.json.JsonElement
import com.colink.android.data.remote.dto.AttachmentReferencesDto
import com.colink.android.data.remote.dto.AttachmentDto
import com.colink.android.data.remote.dto.ChangesDto
import com.colink.android.data.remote.dto.NoteCreateRequestDto
import com.colink.android.data.remote.dto.NoteDeleteDto
import com.colink.android.data.remote.dto.NoteDto
import com.colink.android.data.remote.dto.NoteUpdateRequestDto
import com.colink.android.data.remote.dto.NotesListDto
import com.colink.android.data.remote.dto.SnapshotDto
import com.colink.android.data.remote.dto.StorageDto
import com.colink.android.data.remote.dto.TagCreateRequestDto
import com.colink.android.data.remote.dto.TagDeleteDto
import com.colink.android.data.remote.dto.TagDto
import com.colink.android.data.remote.dto.TagListDto
import com.colink.android.data.remote.dto.TagUpdateRequestDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface NotesApi {
    @GET
    suspend fun listNotes(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Query("tagId") tagId: String? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("limit") limit: Int? = null,
    ): ApiEnvelope<NotesListDto>

    @GET
    suspend fun getNote(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<NoteDto>

    @POST
    suspend fun createNote(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: NoteCreateRequestDto,
    ): ApiEnvelope<NoteDto>

    @PUT
    suspend fun updateNote(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: NoteUpdateRequestDto,
    ): ApiEnvelope<NoteDto>

    @DELETE
    suspend fun deleteNote(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<NoteDeleteDto>

    @GET
    suspend fun listTags(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<TagListDto>

    @POST
    suspend fun createTag(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: TagCreateRequestDto,
    ): ApiEnvelope<TagDto>

    @PUT
    suspend fun updateTag(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: TagUpdateRequestDto,
    ): ApiEnvelope<TagDto>

    @DELETE
    suspend fun deleteTag(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<TagDeleteDto>

    @GET
    suspend fun snapshot(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Query("pageToken") pageToken: String? = null,
        @Query("limit") limit: Int? = null,
    ): ApiEnvelope<SnapshotDto>

    @GET
    suspend fun changes(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: String,
        @Query("limit") limit: Int? = null,
    ): ApiEnvelope<ChangesDto>

    @GET
    suspend fun storage(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<StorageDto>

    @GET
    suspend fun attachmentMetadata(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<AttachmentDto>

    @DELETE
    suspend fun deleteAttachment(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ApiEnvelope<JsonElement>

    @GET
    suspend fun attachmentReferences(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Query("pageToken") pageToken: String? = null,
        @Query("limit") limit: Int? = null,
    ): ApiEnvelope<AttachmentReferencesDto>

    @Multipart
    @POST
    suspend fun uploadAttachment(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Part attachmentId: MultipartBody.Part,
        @Part kind: MultipartBody.Part,
        @Part sha256: MultipartBody.Part,
        @Part file: MultipartBody.Part,
    ): ApiEnvelope<AttachmentDto>

    @Streaming
    @GET
    suspend fun downloadAttachment(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("If-None-Match") ifNoneMatch: String? = null,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>
}
