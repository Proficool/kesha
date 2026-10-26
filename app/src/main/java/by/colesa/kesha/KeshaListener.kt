package by.colesa.kesha

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Обёртка над системным [SpeechRecognizer].
 *
 * Настроена под пожилую речь (раздел 6.1 спецификации): длинные паузы тишины,
 * минимальная длина фразы, промежуточные результаты на экран.
 *
 * ВАЖНО: объект живёт строго в главном потоке — этого требует SpeechRecognizer.
 */
internal class KeshaListener(
    private val context: Context,
    private val onRecordingStarted: () -> Unit,
    private val onRecordingStopped: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onFailed: (Int) -> Unit,
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /**
     * Есть ли на телефоне служба распознавания. Без Google-сервисов (китайские прошивки,
     * Huawei без GMS) вернёт false — приложение обязано сказать об этом голосом.
     */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val service = recognizer ?: create()
        if (service == null) {
            onFailed(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        listening = true
        try {
            service.startListening(buildIntent())
        } catch (e: Exception) {
            listening = false
            onFailed(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** Идёт ли запись прямо сейчас. Нужно, чтобы экран не залип в состоянии «говорю». */
    fun isActive(): Boolean = listening

    /** «Я закончил» — не ждём паузы, но и не выбрасываем уже сказанное. */
    fun stopListening() {
        if (!listening) return
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
        }
    }

    fun cancel() {
        listening = false
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
    }

    fun destroy() {
        listening = false
        val service = recognizer
        recognizer = null
        try {
            service?.destroy()
        } catch (e: Exception) {
        }
    }

    private fun create(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
    } catch (e: Exception) {
        null
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

        // Тихая речь с паузами: не обрываем запись на первой же заминке.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            1500L,
        )
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
    }

    private fun finishRecording() {
        if (!listening) return
        listening = false
        onRecordingStopped()
    }

    private fun bestText(bundle: Bundle?): String {
        val variants = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val first = variants?.firstOrNull() ?: return ""
        return first.trim()
    }

    // --- RecognitionListener -------------------------------------------------

    override fun onReadyForSpeech(params: Bundle?) {
        onRecordingStarted()
    }

    override fun onBeginningOfSpeech() {
    }

    override fun onRmsChanged(rmsdB: Float) {
    }

    override fun onBufferReceived(buffer: ByteArray?) {
    }

    override fun onEndOfSpeech() {
        finishRecording()
    }

    override fun onError(error: Int) {
        finishRecording()
        onFailed(error)
    }

    override fun onResults(results: Bundle?) {
        finishRecording()
        onHeard(bestText(results))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = bestText(partialResults)
        if (text.isNotEmpty()) onPartial(text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
    }

    private companion object {
        const val LANGUAGE = "ru-RU"
    }
}
