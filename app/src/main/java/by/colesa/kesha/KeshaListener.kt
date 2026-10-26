package by.colesa.kesha

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Коды отказов, которых нет в [SpeechRecognizer]: приходят, когда служба распознавания
 * так и не отозвалась. Лежат отдельно, чтобы MainActivity могла их различить.
 */
internal object KeshaFailure {
    /** Службы распознавания на телефоне нет вовсе (нет Google-сервисов). */
    const val NO_SERVICE = -1001

    /** Служба есть, но на запуск записи не отвечает. */
    const val NOT_RESPONDING = -1002

    /** Запись пошла, но микрофон не слышит ни звука — обычно закрыт доступ в прошивке. */
    const val SILENT_MIC = -1003
}

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

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var confirmed = false
    private var attempts = 0
    private var confirmTimer: Runnable? = null
    private var silenceTimer: Runnable? = null
    private var heardSomething = false

    /**
     * Сколько служб распознавания видит приложение.
     *
     * На Android 11+ список непустой только потому, что в манифесте объявлен
     * `<queries><intent><action android:name="android.speech.RecognitionService"/>`.
     * Без него здесь будет 0, и `SpeechRecognizer` не подключится.
     */
    fun serviceCount(): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .size
    } catch (e: Exception) {
        -1
    }

    /** Есть ли на телефоне служба распознавания. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Пакеты служб распознавания, которые видит приложение.
     *
     * Нужно для диагностики: если служба в системе есть, но на запуск записи не
     * отвечает, важно знать, какая именно. Имя пакета сразу показывает, Google это,
     * служба прошивки или что-то постороннее.
     */
    fun serviceNames(): String = try {
        @Suppress("DEPRECATION")
        val list = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        if (list.isEmpty()) {
            "нет"
        } else {
            list.mapNotNull { it.serviceInfo?.packageName }.distinct().sorted().joinToString(", ")
        }
    } catch (e: Exception) {
        "ошибка"
    }

    /**
     * Заранее подключается к службе распознавания.
     *
     * Это не оптимизация, а лечение ошибки: `SpeechRecognizer.startListening()`
     * при ещё не подключённой службе **молча ничего не делает** — ни записи,
     * ни колбэка, ни ошибки. Приложение просто застывает на красной кнопке.
     * Вызываем заранее, чтобы к моменту нажатия связь уже была установлена.
     */
    fun warmUp() {
        if (recognizer == null) recognizer = create()
    }

    /** Идёт ли запись прямо сейчас. Нужно, чтобы экран не залип в состоянии «говорю». */
    fun isActive(): Boolean = listening

    fun start() {
        if (recognizer == null) recognizer = create()
        val service = recognizer
        if (service == null) {
            Log.w(TAG, "createSpeechRecognizer вернул null")
            onFailed(KeshaFailure.NO_SERVICE)
            return
        }
        listening = true
        confirmed = false
        attempts = 0
        heardSomething = false
        attemptStart()
    }

    /**
     * Повторяет запуск, пока служба не подтвердит готовность через `onReadyForSpeech`.
     * Без этого молчаливый отказ `startListening()` выглядит для человека как зависание.
     */
    private fun attemptStart() {
        if (!listening) return
        attempts += 1

        // Первая попытка идёт через прогретый распознаватель. Со второй — пересоздаём:
        // SpeechRecognizer подключается к службе ровно один раз, и если bindService
        // не удался, сам он больше не пытается. Повторять startListening по такому
        // объекту бесполезно — он навсегда остаётся с пустой службой.
        if (attempts > 1) rebind()

        val service = recognizer
        if (service == null) {
            Log.w(TAG, "распознаватель не создался")
            finishRecording()
            onFailed(KeshaFailure.NO_SERVICE)
            return
        }

        try {
            service.startListening(buildIntent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening бросил исключение", e)
            finishRecording()
            onFailed(KeshaFailure.NOT_RESPONDING)
            return
        }

        if (attempts >= MAX_START_ATTEMPTS) {
            Log.w(TAG, "служба не отозвалась за $attempts попыток; службы: ${serviceNames()}")
            finishRecording()
            onFailed(KeshaFailure.NOT_RESPONDING)
            return
        }
        scheduleConfirm()
    }

    /** Пересоздаёт распознаватель, чтобы он заново подключился к службе. */
    private fun rebind() {
        val old = recognizer
        recognizer = null
        try {
            old?.destroy()
        } catch (e: Exception) {
        }
        recognizer = create()
    }

    private fun scheduleConfirm() {
        cancelConfirm()
        val runnable = Runnable {
            confirmTimer = null
            if (listening && !confirmed) {
                Log.i(TAG, "onReadyForSpeech не пришёл, попытка ${attempts + 1}")
                attemptStart()
            }
        }
        confirmTimer = runnable
        main.postDelayed(runnable, START_CONFIRM_MS)
    }

    private fun cancelConfirm() {
        confirmTimer?.let { main.removeCallbacks(it) }
        confirmTimer = null
    }

    /**
     * Если запись пошла, но за [SILENCE_WATCHDOG_MS] не поймано ни звука — микрофон
     * закрыт в прошивке (частая история на MIUI и EMUI) либо неисправен.
     * Молчать об этом нельзя: человек будет ждать ответа в пустоту.
     */
    private fun armSilenceWatchdog() {
        cancelSilenceWatchdog()
        val runnable = Runnable {
            silenceTimer = null
            if (listening && !heardSomething) {
                Log.w(TAG, "ни одного звука за $SILENCE_WATCHDOG_MS мс")
                finishRecording()
                onFailed(KeshaFailure.SILENT_MIC)
            }
        }
        silenceTimer = runnable
        main.postDelayed(runnable, SILENCE_WATCHDOG_MS)
    }

    private fun cancelSilenceWatchdog() {
        silenceTimer?.let { main.removeCallbacks(it) }
        silenceTimer = null
    }

    /** «Я закончил» — не ждём паузы, но и не выбрасываем уже сказанное. */
    fun stopListening() {
        if (!listening) return
        cancelConfirm()
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
        }
    }

    fun cancel() {
        cancelConfirm()
        cancelSilenceWatchdog()
        listening = false
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
    }

    fun destroy() {
        cancelConfirm()
        cancelSilenceWatchdog()
        listening = false
        val service = recognizer
        recognizer = null
        try {
            service?.destroy()
        } catch (e: Exception) {
        }
    }

    private fun create(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(context)
    } catch (e: Exception) {
        Log.w(TAG, "createSpeechRecognizer не удался", e)
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
        cancelConfirm()
        cancelSilenceWatchdog()
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
        confirmed = true
        cancelConfirm()
        Log.i(TAG, "запись началась с попытки $attempts")
        armSilenceWatchdog()
        onRecordingStarted()
    }

    override fun onBeginningOfSpeech() {
        heardSomething = true
        cancelSilenceWatchdog()
    }

    override fun onRmsChanged(rmsdB: Float) {
    }

    override fun onBufferReceived(buffer: ByteArray?) {
    }

    override fun onEndOfSpeech() {
        finishRecording()
    }

    override fun onError(error: Int) {
        Log.w(TAG, "onError, код $error")
        finishRecording()
        onFailed(error)
    }

    override fun onResults(results: Bundle?) {
        finishRecording()
        onHeard(bestText(results))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = bestText(partialResults)
        if (text.isNotEmpty()) {
            heardSomething = true
            cancelSilenceWatchdog()
            onPartial(text)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
    }

    private companion object {
        const val TAG = "Kesha"
        const val LANGUAGE = "ru-RU"

        /** Сколько раз пробовать запустить запись, пока служба не отзовётся. */
        const val MAX_START_ATTEMPTS = 6

        /** Пауза между попытками. */
        const val START_CONFIRM_MS = 1_200L

        /** Сколько ждать хоть какого-нибудь звука, прежде чем признать микрофон глухим. */
        const val SILENCE_WATCHDOG_MS = 12_000L
    }
}
