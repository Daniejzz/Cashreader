package com.example.pruebas
import android.Manifest
import com.example.pruebas.CameraActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var btnIniciar: Button
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val REQ_AUDIO = 100
    private var autoCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        autoCapture = intent.getStringExtra("accion") == "capturar"

        btnIniciar = findViewById(R.id.btnIniciar)

        // Inicializar TTS
        tts = TextToSpeech(this, this)

        btnIniciar.setOnClickListener {
            abrirCamara()
        }

        // Pedir permiso de micrófono
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // No iniciamos escucha aquí, la iniciamos después del mensaje de bienvenida
        } else {
            Toast.makeText(this, "Se necesita permiso de micrófono", Toast.LENGTH_LONG).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Configurar idioma
            tts.language = Locale("es", "ES")

            // Configurar voz femenina si está disponible
            val voces = tts.voices
            val vozFemenina = voces.find { it.locale.language == "es" && it.name.contains("female", ignoreCase = true) }
            if (vozFemenina != null) {
                tts.voice = vozFemenina
            }

            // Decir mensaje y luego iniciar escucha
            tts.speak(
                "Bienvenido a Cashreader, para comenzar toque el botón o diga iniciar",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "mensajeBienvenida"
            )

            // Listener para cuando termine el mensaje
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        iniciarEscuchaVoz()
                    }
                }
            })
        }
    }

    private fun abrirCamara() {
        startActivity(Intent(this, CameraActivity::class.java))
    }

    private fun iniciarEscuchaVoz() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        }

        val listener = object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                speechRecognizer.startListening(intent) // Reinicia escucha
            }

            override fun onResults(results: Bundle?) {
                val palabras = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val comando = palabras?.firstOrNull()?.lowercase()

                when {
                    comando?.contains("iniciar") == true -> abrirCamara()
                    comando?.contains("capturar") == true -> {
                        val intent = Intent(this@MainActivity, CameraActivity::class.java)
                        intent.putExtra("accion", "capturar")
                        startActivity(intent)
                    }
                    else -> speechRecognizer.startListening(intent)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer.setRecognitionListener(listener)
        speechRecognizer.startListening(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        tts.stop()
        tts.shutdown()
    }
}