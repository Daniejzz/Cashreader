package com.example.pruebas.net

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class Detection(val label: String, val confidence: Float)
data class PredictionResponse(val detections: List<Detection>)

interface ApiService {
    @Multipart
    @POST("predict")
    suspend fun predict(@Part file: MultipartBody.Part): Response<PredictionResponse>
}