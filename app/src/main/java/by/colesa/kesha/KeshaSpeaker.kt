package by.colesa.kesha

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Озвучка через системный [TextToSpeech].
 *
 * В APK не кладём ни одного мегабайта голоса: если русского движка на телефоне нет,
 * сообщаем об этом наружу через [onEngineState] — приложение покажет кнопку установки,
 * а не промолчит (раздел 6.3 спецификации).
 *
 * Все колбэки приходят в главном потоке.
 */
internal class KeshaSpeaker(
    context: Context,
    private val onEngineState: (engineOk: Boolean, russianVoiceOk: Boolean) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: () -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var counter = 0
    private var initRetries = 0

    /** Доступен ли русский голос. До инициализации — false. */
    var russianVoiceAvailable = false
        private set

    init {
        // Движок создаём строго в главном потоке: иначе колбэк onInit может прийти
        // раньше, чем конструктор вернёт управление, и поле engine окажется пустым.
        engine = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val tts = engine
        if (tts == null) {
            // Редкий случай: движок отозвался раньше, чем конструктор вернул управление.
            // Подождём немного, но не бесконечно — иначе экран залипнет молча.
            if (initRetries < INIT_RETRY_LIMIT) {
                initRetries += 1
                main.postDelayed({ onInit(status) }, INIT_RETRY_MS)
            } else {
                onEngineState(false, false)
            }
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            russianVoiceAvailable = false
            onEngineState(false, false)
            drainPending()
            return
        }

        val languageResult = try {
            tts.setLanguage(RUSSIAN)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        russianVoiceAvailable =
            languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED

        // Пожилому человеку диктор должен читать не спеша.
        tts.setSpeechRate(if (russianVoiceAvailable) SPEECH_RATE_RUSSIAN else 1.0f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { onSpeechStart() }
            }

            override fun onDone(utteranceId: String?) {
                main.post { onSpeechEnd() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onSpeechEnd() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { onSpeechEnd() }
            }
        })

        ready = true
        onEngineState(true, russianVoiceAvailable)
        drainPending()
    }

    /**
     * Произносит [text]. Если движок ещё просыпается, фраза встаёт в очередь
     * и будет сказана сразу после инициализации — так приветствие не теряется.
     */
    fun speak(text: String) {
        if (text.isEmpty()) {
            onSpeechEnd()
            return
        }
        val tts = engine
        if (!ready || tts == null) {
            pending = text
            return
        }
        counter += 1
        val utteranceId = "kesha-$counter"
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            onSpeechEnd()
        }
    }

    /** Прерывает текущую фразу (например, человек уже нажал кнопку и хочет говорить). */
    fun stop() {
        pending = null
        try {
            engine?.stop()
        } catch (e: Exception) {
            // Движок уже мог умереть — это не повод падать.
        }
    }

    fun shutdown() {
        pending = null
        ready = false
        val tts = engine
        engine = null
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
    }

    private fun drainPending() {
        val text = pending ?: return
        pending = null
        if (ready) {
            speak(text)
        } else {
            // Голоса на телефоне нет вообще: сказать нечего, но экран обязан
            // вернуться в спокойное состояние, а не залипнуть на «отвечаю».
            onSpeechEnd()
        }
    }

    private companion object {
        val RUSSIAN: Locale = Locale("ru", "RU")

        const val SPEECH_RATE_RUSSIAN = 0.92f
        const val INIT_RETRY_LIMIT = 20
        const val INIT_RETRY_MS = 50L
    }
}
