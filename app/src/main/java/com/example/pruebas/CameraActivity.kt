package com.example.pruebas

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
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
import androidx.core.app.ActivityCompat
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
import java.text.SimpleDateFormat
import java.util.Locale

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapturar: Button
    private lateinit var txtResultado: TextView
    private lateinit var iconListening: ImageView
    private lateinit var progressBar: ProgressBar
    private var imageCapture: ImageCapture? = null

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recogIntent: Intent
    private var isProcessing = false
    private var lastCorrectionTs = 0L
    private val REQ_AUDIO = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        btnCapturar = findViewById(R.id.btnCapturar)
        txtResultado = findViewById(R.id.txtResultado)
        iconListening = findViewById(R.id.iconListening)
        progressBar = findViewById(R.id.progressBar)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recogIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        }

        btnCapturar.setOnClickListener { startProcessingAndCapture() }

        // Pedir permisos necesarios una sola vez
        checkPermissions()

        setupRecognitionListener()
    }

    private fun checkPermissions() {
        // Cámara
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Audio (una sola vez por sesión/instalación)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private fun setupRecognitionListener() {
        speechRecognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { iconListening.visibility = View.VISIBLE }
            override fun onBeginningOfSpeech() { iconListening.visibility = View.VISIBLE }
            override fun onEndOfSpeech() { iconListening.visibility = View.GONE }

            override fun onError(error: Int) {
                iconListening.visibility = View.GONE
                if (!isProcessing && hasMicPerm()) startListeningSafe()
                Log.w("SR", "onError=$error")
            }

            override fun onResults(results: Bundle?) {
                iconListening.visibility = View.GONE
                if (isProcessing) return

                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val cmd = words.firstOrNull()?.lowercase() ?: ""

                if ("capturar" in cmd) {
                    startProcessingAndCapture()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastCorrectionTs > 3000) {
                        lastCorrectionTs = now
                        if (ttsReady) {
                            tts.speak("No entendí. Repite: capturar",
                                TextToSpeech.QUEUE_FLUSH, null, "corrige_cmd")
                        }
                        tts.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener(){
                            override fun onStart(id: String?) {}
                            override fun onError(id: String?) {}
                            override fun onDone(id: String?) {
                                runOnUiThread { if (!isProcessing && hasMicPerm()) startListeningSafe() }
                            }
                        })
                    } else {
                        if (hasMicPerm()) startListeningSafe()
                    }
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun hasMicPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListeningSafe() {
        if (!hasMicPerm()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconocimiento no disponible", Toast.LENGTH_LONG).show()
            return
        }
        try {
            speechRecognizer.startListening(recogIntent)
            iconListening.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("SR", "startListening error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isProcessing) startListeningSafe()
    }

    override fun onPause() {
        if (this::speechRecognizer.isInitialized) speechRecognizer.stopListening()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListeningSafe()
        }
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) tts.language = Locale("es", "ES")
    }

    override fun onDestroy() {
        if (this::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (this::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                if (ttsReady) {
                    tts.speak("Di capturar o pulsa el botón",
                        TextToSpeech.QUEUE_FLUSH, null, "indicacion")
                    tts.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onError(id: String?) {}
                        override fun onDone(id: String?) {
                            runOnUiThread { if (!isProcessing) startListeningSafe() }
                        }
                    })
                } else {
                    startListeningSafe()
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
                    setLoading(false)
                    if (hasMicPerm()) startListeningSafe()
                    Toast.makeText(baseContext, "Error al guardar foto", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { uploadImage(it) }
                }
            }
        )
    }

    private fun setLoading(loading: Boolean) {
        isProcessing = loading
        btnCapturar.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun startProcessingAndCapture() {
        setLoading(true)
        if (this::speechRecognizer.isInitialized) speechRecognizer.stopListening()
        if (ttsReady) tts.speak("Procesando, por favor espere un momento", TextToSpeech.QUEUE_FLUSH, null, "procesando, por favor espere un momento")
        takePhoto()
    }

    private fun compressImage(uri: Uri, maxSize: Int = 720, quality: Int = 70): ByteArray {
        val input = contentResolver.openInputStream(uri)!!
        val bmp = BitmapFactory.decodeStream(input)
        input.close()

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
            } finally {
                setLoading(false)
                if (hasMicPerm()) startListeningSafe()
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