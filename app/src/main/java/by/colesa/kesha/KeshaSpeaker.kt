package by.colesa.kesha

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Озвучка через системный [TextToSpeech].
 *
 * В APK не кладём ни одного мегабайта голоса: если русского движка на телефоне нет,
 * сообщаем об этом наружу через [onEngineState] — приложение покажет кнопку установки,
 * а молчать не станет (раздел 6.3 спецификации).
 *
 * Голос **не подменяем и тон не трогаем**. Дуся говорит тем женским голосом, который
 * выбран в настройках телефона, — и это осознанное решение, а не лень.
 *
 * Раньше здесь была охота за мужским голосом: Иннокентий мужчина, а синтезатор по
 * умолчанию берёт женский. Охота провалилась, и вот почему. Пола у [android.speech.tts.Voice]
 * в API нет вообще — ни поля, ни признака. Единственная зацепка — имя голоса, и у
 * некоторых движков пол в него действительно зашит (`en-us-x-iom#male_1-local`).
 * У русских голосов Google такой пометки нет: проверено на живом телефоне. Оставалось
 * понижать тон, но это сдвигает высоту, а не форманты, — получался «женский, но
 * глубокий» голос, что человека и не устроило.
 *
 * Поэтому вместо подмены голоса — смена имени помощницы. Дуся и говорит женским
 * голосом, и противоречия между именем и голосом больше нет.
 *
 * Все колбэки приходят в главном потоке.
 */
internal class KeshaSpeaker(
    context: Context,
    private val onEngineState: (engineOk: Boolean, russianVoiceOk: Boolean) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: () -> Unit,
) {

    private val appContext: Context = context.applicationContext
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
        engine = TextToSpeech(appContext) { status -> onInit(status) }
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

        // Тон ровно 1.0: голос телефона звучит как задумано. setLanguage сбрасывает
        // и голос, и тон, поэтому выставляем после него.
        try {
            tts.setPitch(NATURAL_PITCH)
        } catch (e: Exception) {
        }

        // Пожилому человеку диктор должен читать не спеша.
        tts.setSpeechRate(if (russianVoiceAvailable) SPEECH_RATE_RUSSIAN else 1.0f)
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

        Log.i(
            TAG,
            "голос: русский=$russianVoiceAvailable, движок=${tts.defaultEngine}, " +
                "тон=$NATURAL_PITCH, " +
                "темп=${if (russianVoiceAvailable) SPEECH_RATE_RUSSIAN else 1.0f}",
        )

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
        const val TAG = "Kesha"

        val RUSSIAN: Locale = Locale("ru", "RU")

        const val SPEECH_RATE_RUSSIAN = 0.92f

        /** Обычный тон: голос телефона не искажаем. */
        const val NATURAL_PITCH = 1.0f

        const val INIT_RETRY_LIMIT = 20
        const val INIT_RETRY_MS = 50L
    }
}
