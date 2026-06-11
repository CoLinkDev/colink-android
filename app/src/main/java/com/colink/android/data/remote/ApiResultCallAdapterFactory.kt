package com.colink.android.data.remote

import com.colink.android.data.remote.dto.ApiException
import com.colink.android.data.remote.dto.ErrorEnvelope
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlinx.serialization.json.Json
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit

class ApiResultCallAdapterFactory(
    private val json: Json,
) : CallAdapter.Factory() {
    override fun get(
        returnType: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): CallAdapter<*, *>? {
        if (getRawType(returnType) != Call::class.java) {
            return null
        }
        check(returnType is ParameterizedType) { "Call return type must be parameterized" }
        val responseType = getParameterUpperBound(0, returnType)
        return ApiResultCallAdapter<Any>(responseType, json)
    }
}

private class ApiResultCallAdapter<T>(
    private val responseType: Type,
    private val json: Json,
) : CallAdapter<T, Call<T>> {
    override fun responseType(): Type = responseType

    override fun adapt(call: Call<T>): Call<T> =
        ApiResultCall(call, json)
}

private class ApiResultCall<T>(
    private val delegate: Call<T>,
    private val json: Json,
) : Call<T> by delegate {
    override fun enqueue(callback: Callback<T>) {
        delegate.enqueue(
            object : Callback<T> {
                override fun onResponse(call: Call<T>, response: Response<T>) {
                    if (response.isSuccessful) {
                        callback.onResponse(this@ApiResultCall, response)
                        return
                    }
                    callback.onFailure(this@ApiResultCall, response.toApiException(json))
                }

                override fun onFailure(call: Call<T>, t: Throwable) {
                    callback.onFailure(this@ApiResultCall, t)
                }
            },
        )
    }

    override fun execute(): Response<T> {
        val response = delegate.execute()
        if (!response.isSuccessful) {
            throw response.toApiException(json)
        }
        return response
    }

    override fun clone(): Call<T> =
        ApiResultCall(delegate.clone(), json)
}

private fun Response<*>.toApiException(json: Json): Throwable {
    val raw = errorBody()?.string()
    val envelope = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { body -> runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull() }
    return if (envelope != null) {
        ApiException(envelope.code, envelope.message)
    } else {
        HttpException(this)
    }
}
