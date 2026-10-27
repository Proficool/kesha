package by.colesa.kesha

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import java.util.UUID

/**
 * Единственный экран приложения.
 *
 * Логика предельно простая: одна кнопка, четыре состояния, ни меню, ни настроек,
 * ни регистрации (раздел 5 спецификации). Вся «умная» часть живёт в n8n.
 */
class MainActivity : Activity() {

    private lateinit var ui: KeshaUi
    private lateinit var api: KeshaApi
    private lateinit var haptics: KeshaHaptics
    private lateinit var speaker: KeshaSpeaker
    private lateinit var listener: KeshaListener

    private val main = Handler(Looper.getMainLooper())

    private var state = KeshaState.IDLE
    private var sessionId = ""
    private var lastAnswer: String? = null
    private var recognitionAvailable = true

    private var listenWatchdog: Runnable? = null
    private var speechWatchdog: Runnable? = null


    private var askAfterPermission = false
    private var requestedThisRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = loadSessionId()

        ui = KeshaUi(this)
        ui.onMic = { onMicPressed() }
        ui.onRepeat = { repeatAnswer() }
        ui.onInstallVoice = { openVoiceInstaller() }
        setContentView(ui.root)

        haptics = KeshaHaptics(this, ui.root)
        api = KeshaApi()

        listener = KeshaListener(
            context = this,
            onRecordingStarted = { onRecordingStarted() },
            onRecordingStopped = { haptics.tap() },
            onPartial = { ui.showListening(it) },
            onHeard = { onHeard(it) },
            onFailed = { onRecognitionFailed(it) },
        )

        speaker = KeshaSpeaker(
            context = this,
            onEngineState = { engineOk, russianOk -> onEngineState(engineOk, russianOk) },
            onSpeechStart = { },
            onSpeechEnd = { onSpeechEnd() },
        )

        // Подключаемся к службе распознавания заранее, не дожидаясь нажатия кнопки:
        // startListening() по неподключённой службе молча ничего не делает.
        listener.warmUp()
        // И отдельно проверяем своим bindService, подключается ли приложение к службе
        // вообще. SpeechRecognizer результата подключения не сообщает, а без этой пробы
        // «не подключились» и «подключились, но служба молчит» неотличимы.
        listener.probeBind()
        recognitionAvailable = listener.isAvailable()
        val services = listener.serviceCount()
        Log.i(
            TAG,
            "старт: распознавание=$recognitionAvailable, служб распознавания=$services, " +
                "версия Android=${Build.VERSION.SDK_INT}",
        )
        if (services == 0) {
            // Совсем без служб работать нечему: предупреждаем сразу, а не по нажатию.
            Log.w(TAG, "в системе не найдено ни одной службы распознавания речи")
        }

        ui.setState(KeshaState.IDLE)
        ui.setRepeatEnabled(false)
        ui.showHint("")

        lastAnswer = savedInstanceState?.getString(KEY_STATE_ANSWER)
        if (lastAnswer != null) {
            ui.showAnswer(lastAnswer!!)
            ui.setRepeatEnabled(true)
        }

        if (!recognitionAvailable) {
            // Раздел 6.2: телефон без Google-сервисов обязан получить объяснение, а не тишину.
            ui.showHint("На телефоне нет распознавания речи.")
        }
    }

    override fun onResume() {
        super.onResume()
        maybeAskPermission()
        maybeGreet()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_ANSWER, lastAnswer)
    }

    override fun onDestroy() {
        disarmListenWatchdog()
        disarmSpeechWatchdog()
        listener.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    // --- Единственная кнопка ------------------------------------------------

    private fun onMicPressed() {
        when (state) {
            // Второе нажатие — «я закончил»: не ждём паузы.
            KeshaState.LISTENING -> listener.stopListening()

            // Ответ уже в пути, это 2–3 секунды. Ничего не делаем,
            // чтобы человек не сбил сам себя (раздел 6.6).
            KeshaState.THINKING -> Unit

            // Заговорил — значит, хочет спросить ещё. Обрываем Кешу и слушаем.
            KeshaState.SPEAKING -> beginListening()

            KeshaState.IDLE -> beginListening()
        }
    }

    private fun beginListening() {
        if (!recognitionAvailable) {
            sayQuietly(
                "На этом телефоне не работает распознавание речи. " +
                    "Нужны службы Google. Скажите, пожалуйста, тому, кто устанавливал приложение.",
            )
            return
        }
        if (!ensureAudioPermission()) return

        // Дуся замолкает, как только человек берётся спрашивать: иначе микрофон услышит его же.
        speaker.stop()
        disarmSpeechWatchdog()
        disarmListenWatchdog()
        dischargeAnswer()

        ui.showListening("")
        ui.showHint("Говорите, я слушаю")
        setState(KeshaState.LISTENING)
        Log.i(TAG, "запускаю запись")
        listener.start()
        armListenWatchdog()
    }

    private fun repeatAnswer() {
        val answer = lastAnswer ?: return
        val wasListening = state == KeshaState.LISTENING

        disarmListenWatchdog()
        speaker.stop()
        ui.showAnswer(answer)
        ui.showHint("")
        // Состояние меняем ДО отмены распознавания: поздний ERROR_CLIENT от нашей же
        // отмены не должен выглядеть для человека как «не расслышал».
        setState(KeshaState.SPEAKING)
        if (wasListening) listener.cancel()

        armSpeechWatchdog(answer)
        speaker.speak(answer)
    }

    // --- Запись -------------------------------------------------------------

    private fun onRecordingStarted() {
        if (state != KeshaState.LISTENING) setState(KeshaState.LISTENING)
        ui.showHint("Говорите, я слушаю")
        haptics.tap()
    }

    private fun onHeard(text: String) {
        disarmListenWatchdog()
        if (text.isEmpty()) {
            onRecognitionFailed(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        // Показываем распознанный вопрос крупно: человек видит, что его поняли,
        // и эти 2–3 секунды ожидания не выглядят поломкой.
        ui.showAnswer(text)
        ui.showHint("Спрашиваю у Дуси…")
        setState(KeshaState.THINKING)

        val question = text
        api.ask(question, sessionId) { reply -> onReply(reply) }
    }

    private fun onRecognitionFailed(error: Int) {
        disarmListenWatchdog()

        // ERROR_CLIENT приходит и при нашей собственной отмене — не пугаем человека зря.
        if (error == SpeechRecognizer.ERROR_CLIENT && state != KeshaState.LISTENING) return

        // Технические подробности — в журнал, а не на экран: человеку они ни к чему,
        // но если что-то снова сломается, без них опять придётся гадать.
        Log.w(TAG, "отказ распознавания $error: ${listener.statusLine()}")

        val headline: String
        val message: String
        when (error) {
            KeshaFailure.NO_SERVICE -> {
                headline = "Микрофон не работает"
                message = "На этом телефоне нет службы распознавания речи. " +
                    "Скажите, пожалуйста, тому, кто устанавливал приложение."
            }

            KeshaFailure.NOT_RESPONDING -> {
                headline = "Микрофон не отвечает"
                message = "Служба распознавания речи не отвечает. " +
                    "Попробуйте, пожалуйста, ещё раз или перезапустите приложение."
            }

            KeshaFailure.SILENT_MIC -> {
                headline = "Я вас не слышу"
                message = "Я вас не слышу. Проверьте, пожалуйста, что микрофон " +
                    "не закрыт в настройках телефона, и говорите ближе к нему."
            }

            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> {
                headline = "Не расслышала"
                message = "Я не расслышала. Нажмите, пожалуйста, большую кнопку и скажите ещё раз."
            }

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> {
                headline = "Нет интернета"
                message = "Нет интернета. Проверьте, пожалуйста, связь и попробуйте ещё раз."
            }

            SpeechRecognizer.ERROR_AUDIO -> {
                headline = "Не слышу"
                message = "Не получилось записать звук. Попробуйте, пожалуйста, ещё раз."
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                headline = "Нужно разрешение"
                message = "Разрешите, пожалуйста, доступ к микрофону и попробуйте снова."
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                headline = "Я ещё слушаю"
                message = "Подождите, пожалуйста, секунду и повторите."
            }

            else -> {
                headline = "Не получилось"
                message = "Не получилось разобрать речь. Нажмите, пожалуйста, кнопку и повторите."
            }
        }

        dischargeAnswer()
        ui.showAnswer(headline)
        sayQuietly(message)
    }

    // --- Ответ --------------------------------------------------------------

    private fun onReply(reply: KeshaReply) {
        // Пока сервер думал, человек мог начать новый вопрос — тогда ответ уже не нужен.
        if (state != KeshaState.THINKING) return

        when (reply) {
            is KeshaReply.Answer -> presentAnswer(reply.text)
            is KeshaReply.Failure -> {
                dischargeAnswer()
                ui.showAnswer("Не получилось")
                sayQuietly(reply.spoken)
            }
        }
    }

    private fun presentAnswer(text: String) {
        lastAnswer = text
        ui.showAnswer(text)
        ui.setRepeatEnabled(true)
        ui.showHint("")
        setState(KeshaState.SPEAKING)
        armSpeechWatchdog(text)
        speaker.speak(text)
    }

    private fun onSpeechEnd() {
        disarmSpeechWatchdog()
        if (state == KeshaState.SPEAKING) setState(KeshaState.IDLE)
    }

    // --- Состояние ----------------------------------------------------------

    private fun setState(next: KeshaState) {
        state = next
        ui.setState(next)
        // Раздел 5: экран не гаснет, пока идёт разговор.
        keepScreenOn(next != KeshaState.IDLE)
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dischargeAnswer() {
        lastAnswer = null
        ui.setRepeatEnabled(false)
    }

    /** Говорит фразу, не переводя кнопку в зелёное состояние: это подсказка, а не ответ Дуси. */
    private fun sayQuietly(message: String) {
        if (state != KeshaState.IDLE) setState(KeshaState.IDLE)
        ui.showHint(message)
        speaker.speak(message)
    }

    private fun sayGreeting(text: String) {
        setState(KeshaState.SPEAKING)
        armSpeechWatchdog(text)
        speaker.speak(text)
    }

    // --- Сторожевые таймеры -------------------------------------------------

    private fun armListenWatchdog(delayMs: Long = LISTEN_WATCHDOG_MS) {
        disarmListenWatchdog()
        val runnable = Runnable {
            listenWatchdog = null
            if (state != KeshaState.LISTENING) return@Runnable

            if (listener.isActive()) {
                // Служба замолчала и не отдаёт результат — мягко завершаем запись тем,
                // что успели услышать, и даём ей второй шанс.
                listener.stopListening()
                armListenWatchdog(LISTEN_STUCK_MS)
            } else {
                // Запись уже закончилась, а результата всё нет. Не оставляем человека
                // перед красной кнопкой «говорите» — говорим, что не расслышали.
                onRecognitionFailed(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            }
        }
        listenWatchdog = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun disarmListenWatchdog() {
        listenWatchdog?.let { main.removeCallbacks(it) }
        listenWatchdog = null
    }

    private fun armSpeechWatchdog(text: String) {
        disarmSpeechWatchdog()
        val budget = 15_000L + text.length * 120L
        val runnable = Runnable {
            speechWatchdog = null
            if (state == KeshaState.SPEAKING) setState(KeshaState.IDLE)
        }
        speechWatchdog = runnable
        main.postDelayed(runnable, if (budget > 120_000L) 120_000L else budget)
    }

    private fun disarmSpeechWatchdog() {
        speechWatchdog?.let { main.removeCallbacks(it) }
        speechWatchdog = null
    }

    // --- Приветствие --------------------------------------------------------

    private fun maybeGreet() {
        if (state != KeshaState.IDLE || !recognitionAvailable) {
            markSeen()
            return
        }
        val prefs = prefs()
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SEEN, 0L)
        val isFirstLaunch = last == 0L
        val longPause = !isFirstLaunch && now - last > GREETING_GAP_MS

        if (isFirstLaunch || longPause) {
            ui.showAnswer(GREETING)
            ui.showHint("")
            sayGreeting(GREETING)
        }
        markSeen()
    }

    private fun markSeen() {
        prefs().edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    // --- Разрешения ---------------------------------------------------------

    private fun hasAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Просит доступ к микрофону ровно один раз при первом запуске. */
    private fun maybeAskPermission() {
        if (hasAudioPermission()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (prefs().getBoolean(KEY_AUTO_ASK_DONE, false)) return

        prefs().edit().putBoolean(KEY_AUTO_ASK_DONE, true).apply()
        requestedThisRun = true
        askAfterPermission = false
        main.postDelayed({ requestAudio() }, 900L)
    }

    private fun ensureAudioPermission(): Boolean {
        if (hasAudioPermission()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val shouldExplain = requestedThisRun &&
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (requestedThisRun && !shouldExplain) {
            // Отказано навсегда — система больше не покажет диалог. Остались настройки.
            sayQuietly("Разрешите, пожалуйста, доступ к микрофону — в настройках приложения.")
            openAppSettings()
            return false
        }
        requestedThisRun = true
        askAfterPermission = true
        requestAudio()
        return false
    }

    private fun requestAudio() {
        try {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } catch (e: Exception) {
            sayQuietly("Не получилось запросить доступ к микрофону.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (granted) {
            if (askAfterPermission) {
                askAfterPermission = false
                beginListening()
            }
            return
        }

        askAfterPermission = false
        val canAskAgain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (canAskAgain) {
            sayQuietly(
                "Без разрешения на микрофон я вас не услышу. " +
                    "Нажмите, пожалуйста, большую кнопку и разрешите доступ.",
            )
        } else {
            sayQuietly("Разрешите, пожалуйста, доступ к микрофону — в настройках приложения.")
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: Exception) {
            // Настройки недоступны — фраза уже произнесена, этого достаточно.
        }
    }

    // --- Голос --------------------------------------------------------------

    private fun onEngineState(engineOk: Boolean, russianOk: Boolean) {
        val needsVoice = !engineOk || !russianOk
        ui.setVoiceButtonVisible(needsVoice)
        if (needsVoice) {
            // Раздел 6.3: не молчим, а предлагаем скачать голос.
            ui.showHint("Русский голос не установлен. Нажмите «Установить русский голос».")
        }
    }

    private fun openVoiceInstaller() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            return
        } catch (e: ActivityNotFoundException) {
            // Пробуем штатные настройки синтеза речи.
        } catch (e: Exception) {
        }
        try {
            startActivity(Intent(ACTION_TTS_SETTINGS))
        } catch (e: Exception) {
            sayQuietly(
                "Не получилось открыть установку голоса. Попросите, пожалуйста, помощи.",
            )
        }
    }

    // --- Мелочи -------------------------------------------------------------

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    /**
     * Постоянный идентификатор устройства. По нему сервер помнит контекст разговора,
     * поэтому генерируется один раз и больше не меняется (раздел 3 спецификации).
     */
    private fun loadSessionId(): String {
        val store = prefs()
        val existing = store.getString(KEY_SESSION_ID, null)
        if (!existing.isNullOrEmpty()) return existing
        val generated = UUID.randomUUID().toString()
        store.edit().putString(KEY_SESSION_ID, generated).apply()
        return generated
    }

    private companion object {
        const val TAG = "Kesha"
        const val PREFS_NAME = "kesha"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_LAST_SEEN = "lastSeen"
        const val KEY_AUTO_ASK_DONE = "autoAskDone"
        const val KEY_STATE_ANSWER = "stateAnswer"

        const val REQUEST_AUDIO = 1001

        const val LISTEN_WATCHDOG_MS = 20_000L
        const val LISTEN_STUCK_MS = 4_000L
        const val GREETING_GAP_MS = 15 * 60 * 1000L

        const val GREETING = "Здравствуйте! Я Дуся. Нажмите большую кнопку и спросите меня."
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
    }
}
