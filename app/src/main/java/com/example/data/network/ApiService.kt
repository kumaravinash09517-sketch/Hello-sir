package com.example.data.network

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface ApiService {

    // ==================== Auth & User Management ====================

    @POST("api/auth/login")
    suspend fun login(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/users/register")
    suspend fun register(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/users/profile")
    suspend fun createOrUpdateProfile(@Body body: RequestBody): Response<ResponseBody>

    @PUT("api/users/profile")
    suspend fun updateProfile(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/users/find")
    suspend fun findUser(
        @Query("userId") userId: String? = null,
        @Query("user_id") userIdSnake: String? = null,
        @Query("username") username: String? = null,
        @Query("phone") phone: String? = null,
        @Query("device_id") deviceId: String? = null
    ): Response<ResponseBody>

    @GET("api/users/search")
    suspend fun searchUsers(@Query("q") query: String): Response<ResponseBody>

    @GET("api/users")
    suspend fun getUsers(): Response<ResponseBody>

    // ==================== Messaging ====================

    @POST("api/messages")
    suspend fun sendMessage(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/messages/{peerId}")
    suspend fun getMessagesWithPeer(
        @Path("peerId") peerId: String,
        @Query("userId") userId: String
    ): Response<ResponseBody>

    @DELETE("api/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("messageId") messageId: String,
        @Query("userId") userId: String? = null
    ): Response<ResponseBody>

    @POST("api/messages/{peerId}/read")
    suspend fun markMessagesRead(
        @Path("peerId") peerId: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    // ==================== Media Upload ====================

    @Multipart
    @POST("api/media/upload")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("userId") userId: RequestBody? = null,
        @Part("mediaType") mediaType: RequestBody? = null
    ): Response<ResponseBody>

    // ==================== WebRTC Calling & Signaling ====================

    @GET("api/webrtc/ice-servers")
    suspend fun getIceServers(): Response<ResponseBody>

    @POST("api/calls/signal")
    suspend fun sendCallSignal(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/calls/poll")
    suspend fun pollCallSignals(
        @Query("user_id") userId: String
    ): Response<ResponseBody>

    @GET("api/calls/active")
    suspend fun getActiveCalls(
        @Query("user_id") userId: String
    ): Response<ResponseBody>
}

object RetrofitClient {
    const val BASE_URL = BackendConfig.RENDER_BASE_URL
    const val API_BASE_URL = BackendConfig.API_BASE_URL
    const val UPLOADS_BASE_URL = BackendConfig.UPLOADS_BASE_URL
    const val WS_URL = BackendConfig.WS_URL

    @Volatile
    var activeUserIdProvider: () -> String? = { null }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(SafeHeaderInterceptor { activeUserIdProvider() })
        .addInterceptor(loggingInterceptor)
        .build()

    val externalHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(loggingInterceptor)
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .build()
            .create(ApiService::class.java)
    }
}
