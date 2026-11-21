package mx.edu.utez.memorias.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    // 127.0.0.1 funciona de forma inmediata con el túnel ADB reverse; 10.0.2.2 es el fallback del emulador
    const val BASE_URL = "http://127.0.0.1:5000/"

    private val fallbackInterceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: Exception) {
            val url = request.url
            val alternateHost = if (url.host == "127.0.0.1") "10.0.2.2" else "127.0.0.1"
            val fallbackUrl = url.newBuilder().host(alternateHost).build()
            chain.proceed(request.newBuilder().url(fallbackUrl).build())
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(fallbackInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun getImageUrl(imageFilename: String?): String? {
        return imageFilename?.takeIf { it.isNotBlank() }?.let { "${BASE_URL}uploads/$it" }
    }
}
