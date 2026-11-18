package com.example.data.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface HelloSirApi {
    // पाइथन सर्वर के असली राउट्स के हिसाब से इन्हें अपडेट कर दिया गया है
    @POST("api/users/register")
    suspend fun register(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/users")
    suspend fun getFeed(): Response<ResponseBody>

    @POST("api/messages")
    suspend fun sendMessage(@Body body: RequestBody): Response<ResponseBody>
}

object RetrofitClient {
    const val BASE_URL = "https://rendar-ud3i.onrender.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    val api: HelloSirApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .build()
            .create(HelloSirApi::class.java)
    }
}
