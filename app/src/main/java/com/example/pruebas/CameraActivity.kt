package com.example.pruebas

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.pruebas.net.PredictionResponse
import com.example.pruebas.net.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import android.view.View
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import androidx.core.app.ActivityCompat
import android.content.Intent


class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapturar: Button
    private lateinit var txtResultado: TextView
    private var imageCapture: ImageCapture? = null
    private var autoCapture =  false

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recogIntent: Intent
    private val REQ_AUDIO = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        autoCapture = intent.getStringExtra("accion") == "capturar"
        previewView = findViewById(R.id.previewView)
        btnCapturar = findViewById(R.id.btnCapturar)
        txtResultado = findViewById(R.id.txtResultado)

        tts = TextToSpeech(this, this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        btnCapturar.setOnClickListener { takePhoto() }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recogIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        }
        speechRecognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { speechRecognizer.startListening(recogIntent) }
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val cmd = words?.firstOrNull()?.lowercase() ?: ""
                if ("capturar" in cmd) takePhoto() else speechRecognizer.startListening(recogIntent)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) tts.language = Locale("es", "ES")
    }

    override fun onDestroy() {
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else { Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show(); finish() }
        }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                if (!autoCapture) {
                    // mensaje TTS
                    previewView.postDelayed({
                        if (ttsReady) {
                            tts.speak("Puedes oprimir el botón o decir capturar",
                                TextToSpeech.QUEUE_FLUSH, null, "indicacion_captura")
                        }
                    }, 400)

                    tts.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener(){
                        override fun onStart(utteranceId: String?) {}
                        override fun onError(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            runOnUiThread { speechRecognizer.startListening(recogIntent) }
                        }
                    })
                } else {
                    previewView.postDelayed({ takePhoto() }, 800)
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Error iniciando la cámara", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cashreader")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Error al guardar foto", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { uploadImage(it) }
                }
            }
        )
    }

    // --- Networking ---

    private fun uriToTempFile(uri: Uri): File {
        val input: InputStream = contentResolver.openInputStream(uri)!!
        val temp = File.createTempFile("upload_", ".jpg", cacheDir)
        FileOutputStream(temp).use { out -> input.copyTo(out) }
        return temp
    }

    private fun compressImage(uri: Uri, maxSize: Int = 1024, quality: Int = 80): ByteArray {
        val input = contentResolver.openInputStream(uri)!!
        val bmp = BitmapFactory.decodeStream(input)
        input.close()

        // Redimensiona si es muy grande
        val ratio = maxOf(bmp.width, bmp.height).toFloat() / maxSize.toFloat()
        val w = if (ratio > 1) (bmp.width / ratio).toInt() else bmp.width
        val h = if (ratio > 1) (bmp.height / ratio).toInt() else bmp.height
        val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)

        val baos = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
        return baos.toByteArray()
    }

    private fun uploadImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { compressImage(uri) }
                val req = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "photo.jpg", req)

                val resp = withContext(Dispatchers.IO) { RetrofitClient.api.predict(part) }
                if (resp.isSuccessful && resp.body() != null) {
                    handlePrediction(resp.body()!!)
                } else {
                    val code = resp.code()
                    val err = resp.errorBody()?.string() ?: "sin cuerpo"
                    Toast.makeText(this@CameraActivity, "HTTP $code: $err", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@CameraActivity, "Network error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handlePrediction(pred: PredictionResponse) {
        val message = if (pred.detections.isEmpty()) {
            "No se detectó billete"
        } else {
            val top = pred.detections.maxByOrNull { it.confidence }!!
            "Su billete es de: ${top.label.replace("_", " ")}"
        }
        txtResultado.visibility = View.VISIBLE
        txtResultado.text = message
        if (ttsReady) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "resultado")
    }


}