package by.colesa.kesha

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Результат одного обращения к Иннокентию.
 *
 * В обоих случаях текст уже готов: его можно и показать, и произнести.
 * Разметку вычищает сервер (см. KESHA-APK-SPEC.md, раздел 3), клиент ничего не чистит.
 */
internal sealed class KeshaReply {
    /** Сервер ответил по существу. */
    class Answer(val text: String) : KeshaReply()

    /** Ответа нет — [spoken] это готовая человеческая фраза-объяснение. */
    class Failure(val spoken: String) : KeshaReply()
}

/**
 * Единственный сетевой выход приложения: POST на вебхук помощницы.
 *
 * Намеренно на голом [HttpURLConnection] из стандартной библиотеки Android:
 * OkHttp/Retrofit добавили бы к APK сотни килобайт (раздел 10 спецификации).
 *
 * Колбэк всегда вызывается в главном потоке.
 */
internal class KeshaApi {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Отправляет вопрос и возвращает ответ через [onReply] в главном потоке.
     * Отмена не предусмотрена: запрос короткий, а результат всё равно проверяется
     * по состоянию экрана на стороне вызывающего.
     */
    fun ask(question: String, sessionId: String, onReply: (KeshaReply) -> Unit) {
        val worker = Thread {
            val reply = perform(question, sessionId)
            main.post { onReply(reply) }
        }
        worker.name = "kesha-http"
        worker.isDaemon = true
        worker.start()
    }

    private fun perform(question: String, sessionId: String): KeshaReply {
        val payload = JSONObject()
            .put("text", question)
            .put("sessionId", sessionId)
            .put("lang", "ru")
            .toString()
            .toByteArray(Charsets.UTF_8)

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Token", APP_TOKEN)
                setFixedLengthStreamingMode(payload.size)
            }

            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_FORBIDDEN) return KeshaReply.Failure(SPEECH_ACCESS)
            if (code < 200 || code > 299) return KeshaReply.Failure(SPEECH_SERVER)

            return parse(connection.inputStream.use { readAll(it) })
        } catch (e: SocketTimeoutException) {
            return KeshaReply.Failure(SPEECH_SLOW)
        } catch (e: UnknownHostException) {
            return KeshaReply.Failure(SPEECH_NO_INTERNET)
        } catch (e: ConnectException) {
            return KeshaReply.Failure(SPEECH_NO_INTERNET)
        } catch (e: javax.net.ssl.SSLException) {
            return KeshaReply.Failure(SPEECH_NO_INTERNET)
        } catch (e: Exception) {
            return KeshaReply.Failure(SPEECH_SERVER)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String): KeshaReply {
        if (body.isEmpty()) return KeshaReply.Failure(SPEECH_SERVER)
        return try {
            val json = JSONObject(body)
            val status = json.optString("status", "")
            val answer = json.optString("answer", "").trim()
            when {
                answer.isEmpty() -> KeshaReply.Failure(SPEECH_EMPTY)
                status == "ok" -> KeshaReply.Answer(answer)
                // status = "error": сервер уже прислал готовую вежливую фразу — произносим её.
                else -> KeshaReply.Failure(answer)
            }
        } catch (e: Exception) {
            KeshaReply.Failure(SPEECH_SERVER)
        }
    }

    private fun readAll(stream: InputStream): String {
        val sink = ByteArrayOutputStream(4096)
        val chunk = ByteArray(4096)
        while (true) {
            val read = stream.read(chunk)
            if (read == -1) break
            sink.write(chunk, 0, read)
        }
        return String(sink.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val ENDPOINT = "https://n8n.microstoma.top/webhook/innokentiy"

        /**
         * Токен зашит в APK осознанно: это барьер против сканеров открытых вебхуков,
         * а не полноценная аутентификация (раздел 3 спецификации).
         */
        const val APP_TOKEN = "508a42cfff893542bececb2c1cca42ad942c51ac1d3187c1"

        /** Сервер отвечает за 2–3 секунды, но таймаут на чтение — не меньше 60 с. */
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000

        const val SPEECH_NO_INTERNET =
            "Нет интернета. Проверьте, пожалуйста, связь и попробуйте ещё раз."
        const val SPEECH_SLOW =
            "Сервер долго не отвечает. Попробуйте, пожалуйста, ещё раз."
        const val SPEECH_SERVER =
            "Не получилось связаться с Дусей. Попробуйте, пожалуйста, ещё раз."
        const val SPEECH_ACCESS =
            "Сервер не пускает приложение. Скажите, пожалуйста, тому, кто его устанавливал."
        const val SPEECH_EMPTY =
            "Дуся не смогла ответить. Спросите, пожалуйста, ещё раз."
    }
}
