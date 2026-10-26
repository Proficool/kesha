package by.colesa.kesha

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Озвучка через системный [TextToSpeech].
 *
 * В APK не кладём ни одного мегабайта голоса: если русского движка на телефоне нет,
 * сообщаем об этом наружу через [onEngineState] — приложение покажет кнопку установки,
 * а не промолчит (раздел 6.3 спецификации).
 *
 * Отдельная забота — **пол голоса**. Иннокентий мужчина, а синтезатор по умолчанию
 * часто берёт женский голос. Сложность в том, что у [Voice] нет признака пола:
 * узнать его из API нельзя. Поэтому действуем в два слоя:
 *
 *  1. Если у русского голоса в имени есть явная пометка «male»/«female» — берём
 *     мужской и читаем его естественным тоном.
 *  2. Если пометки нет (обычный случай), голос не подменяем — остаётся выбранный
 *     в настройках телефона, — но понижаем тон, чтобы голос звучал ниже.
 *
 * Второй слой нужен именно потому, что первый срабатывает не всегда. Отдельного
 * выбора голоса в приложении нет: если в настройках синтезатора на телефоне выбран
 * мужской русский голос, приложение подхватит его самó.
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

    /** Русские голоса, самые «мужские» — первыми. */
    private var voices: List<Voice> = emptyList()

    /** Выбранный голос. -1 означает «не подменять голос телефона». */
    private var voiceIndex = -1

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

        // Порядок важен: setLanguage сбрасывает выбранный голос, поэтому голос и тон
        // выставляем после языка.
        voices = loadRussianVoices(tts)
        voiceIndex = voices.indexOfFirst { isExplicitlyMale(it) }
        applyVoice(tts)
        applyPitch(tts)

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
            "голос: русский=$russianVoiceAvailable, русских голосов=${voices.size}, " +
                "выбран=${describe(voices.getOrNull(voiceIndex))}, " +
                "тон=${if (isExplicitlyMale(voices.getOrNull(voiceIndex))) 1.0f else MALE_PITCH}, " +
                "все голоса=${voices.joinToString { describe(it) }}",
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

    // --- Голоса --------------------------------------------------------------

    private fun loadRussianVoices(tts: TextToSpeech): List<Voice> = try {
        val all = tts.voices
        if (all == null) {
            emptyList()
        } else {
            all.filter { it.locale != null && "ru".equals(it.locale.language, true) }
                .sortedByDescending { masculinity(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "список голосов недоступен", e)
        emptyList()
    }

    /**
     * Насколько голос похож на мужской.
     *
     * Пола в [Voice] нет, поэтому опираемся только на явные пометки в имени и
     * признаках голоса. Коды вроде «rud» намеренно не разгадываем: ошибка здесь
     * хуже, чем честный ноль, — при нуле голос телефона не подменяется.
     */
    private fun masculinity(voice: Voice): Int {
        if (isExplicitlyFemale(voice)) return -FEMALE_BONUS
        if (isExplicitlyMale(voice)) return MALE_BONUS
        return 0
    }

    private fun isExplicitlyMale(voice: Voice?): Boolean {
        val name = voice?.name?.lowercase(Locale.ROOT) ?: return false
        if (name.contains("female")) return false
        if (name.contains("male")) return true
        // Некоторые движки кладут пол в список признаков голоса.
        return voice.features.orEmpty().any { it.lowercase(Locale.ROOT).contains("male") }
    }

    private fun isExplicitlyFemale(voice: Voice?): Boolean {
        val name = voice?.name?.lowercase(Locale.ROOT) ?: return false
        if (name.contains("female")) return true
        return voice?.features.orEmpty().any { it.lowercase(Locale.ROOT).contains("female") }
    }

    private fun describe(voice: Voice?): String {
        if (voice == null) return "голос телефона по умолчанию"
        val kind = when {
            isExplicitlyMale(voice) -> "мужской"
            isExplicitlyFemale(voice) -> "женский"
            else -> "пол неизвестен"
        }
        return "${voice.name} ($kind)"
    }

    /** Подменяем голос телефона только тогда, когда нашли явно мужской. */
    private fun applyVoice(tts: TextToSpeech) {
        val voice = voices.getOrNull(voiceIndex) ?: return
        try {
            tts.voice = voice
        } catch (e: Exception) {
            Log.w(TAG, "не удалось выбрать голос ${voice.name}", e)
        }
    }

    /**
     * Явно мужской голос читаем его собственным тоном. Если пол определить нельзя,
     * понижаем тон: так голос звучит ниже и меньше спорит с именем Иннокентий.
     */
    private fun applyPitch(tts: TextToSpeech) {
        val pitch = if (isExplicitlyMale(voices.getOrNull(voiceIndex))) 1.0f else MALE_PITCH
        try {
            tts.setPitch(pitch)
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val TAG = "Kesha"

        val RUSSIAN: Locale = Locale("ru", "RU")

        const val SPEECH_RATE_RUSSIAN = 0.92f

        /** Тон для голоса, пол которого определить не удалось. */
        const val MALE_PITCH = 0.85f

        const val MALE_BONUS = 100
        const val FEMALE_BONUS = 100

        const val INIT_RETRY_LIMIT = 20
        const val INIT_RETRY_MS = 50L
    }
}
