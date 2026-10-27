package by.colesa.kesha

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Состояния единственного экрана. Цвет большой кнопки однозначно говорит, что происходит. */
internal enum class KeshaState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Весь интерфейс собран кодом: XML-разметки в проекте нет (раздел 4 спецификации).
 *
 * Экран рассчитан на пожилого человека: одна кнопка 240dp, ответ 30sp по центру,
 * большая кнопка «повторить», никаких меню.
 */
internal class KeshaUi(context: Context) {

    var onMic: (() -> Unit)? = null
    var onRepeat: (() -> Unit)? = null
    var onInstallVoice: (() -> Unit)? = null

    val root: View

    private val context: Context = context
    private val answerView: TextView
    private val answerScroll: ScrollView
    private val hintView: TextView
    private val micButton: Button
    private val micIcon: Drawable?
    private val micBackground: GradientDrawable
    private val auraView: View
    private val auraBackground: GradientDrawable
    private val repeatButton: Button
    private val voiceButton: Button

    /**
     * Номер установленной сборки в правом нижнем углу.
     *
     * Стоит здесь не для пожилого человека, а чтобы одним взглядом понять, какая
     * версия реально стоит на телефоне: обновление может молча не установиться
     * (так уже было из-за несовпадения подписи), и тогда на экране остаётся старый
     * номер. Берём его из системы, а не из константы в коде, — иначе покажет
     * не то, что установлено, а то, что собиралось.
     */
    private val versionView: TextView


    private var pulse: ValueAnimator? = null

    init {
        answerView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ANSWER_SP)
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setLineSpacing(dp(context, 6).toFloat(), 1f)
            setPadding(dp(context, 6), dp(context, 14), dp(context, 6), dp(context, 14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        answerScroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(answerView)
        }

        hintView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(COLOR_TEXT_SOFT)
            gravity = Gravity.CENTER
            maxLines = 6
            minHeight = dp(context, 30)
            setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 10))
        }

        micBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
        }

        auraBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 6), COLOR_LISTENING)
        }

        auraView = View(context).apply {
            background = auraBackground
            visibility = View.INVISIBLE
        }

        micIcon = context.getDrawable(R.drawable.ic_mic)?.also {
            val side = dp(context, 44)
            it.setBounds(0, 0, side, side)
        }

        micButton = Button(context).apply {
            isAllCaps = false
            background = micBackground
            setTextColor(COLOR_ON_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            stateListAnimator = null
            elevation = 0f
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            contentDescription = "Кнопка вопроса"
            setOnClickListener { onMic?.invoke() }
            val icon = micIcon
            if (icon != null) {
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(context, 10)
            }
        }

        val micHolder = FrameLayout(context)
        micHolder.addView(
            auraView,
            FrameLayout.LayoutParams(dp(context, MIC_SIZE), dp(context, MIC_SIZE), Gravity.CENTER),
        )
        micHolder.addView(
            micButton,
            FrameLayout.LayoutParams(dp(context, MIC_SIZE), dp(context, MIC_SIZE), Gravity.CENTER),
        )

        repeatButton = Button(context).apply {
            isAllCaps = false
            text = "ПОВТОРИТЬ"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedRect(
                COLOR_ON_DARK,
                dp(context, 18),
                dp(context, 2),
                COLOR_IDLE,
            )
            setTextColor(COLOR_TEXT)
            stateListAnimator = null
            elevation = 0f
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { onRepeat?.invoke() }
        }

        voiceButton = Button(context).apply {
            isAllCaps = false
            text = "УСТАНОВИТЬ РУССКИЙ ГОЛОС"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedRect(COLOR_THINKING, dp(context, 16), 0, COLOR_THINKING)
            setTextColor(COLOR_ON_LIGHT)
            stateListAnimator = null
            elevation = 0f
            visibility = View.GONE
            setOnClickListener { onInstallVoice?.invoke() }
        }

        versionView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(COLOR_TEXT_SOFT)
            alpha = 0.65f
            gravity = Gravity.END
            text = installedVersion(context)
        }


        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 14))
        }

        column.addView(
            answerScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        column.addView(hintView)
        column.addView(
            micHolder,
            LinearLayout.LayoutParams(dp(context, HOLDER_SIZE), dp(context, HOLDER_SIZE)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        column.addView(
            repeatButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 66),
            ).apply { topMargin = dp(context, 10) },
        )
        column.addView(
            voiceButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 58),
            ).apply { topMargin = dp(context, 8) },
        )
        // Отдельной строкой, а не поверх ответа: иначе длинный ответ залезет под номер.
        column.addView(
            versionView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root = column
        setState(KeshaState.IDLE)
    }

    /** Цвет = состояние. Серый — нажми, красный — говори, жёлтый — думаю, зелёный — отвечаю. */
    fun setState(state: KeshaState) {
        val color = when (state) {
            KeshaState.IDLE -> COLOR_IDLE
            KeshaState.LISTENING -> COLOR_LISTENING
            KeshaState.THINKING -> COLOR_THINKING
            KeshaState.SPEAKING -> COLOR_SPEAKING
        }
        val onColor = if (state == KeshaState.THINKING) COLOR_ON_LIGHT else COLOR_ON_DARK

        micBackground.setColor(color)
        micButton.setTextColor(onColor)
        micIcon?.setTint(onColor)
        micButton.text = when (state) {
            KeshaState.IDLE -> "НАЖМИТЕ\nИ ГОВОРИТЕ"
            KeshaState.LISTENING -> "ГОВОРИТЕ"
            KeshaState.THINKING -> "ДУМАЮ"
            KeshaState.SPEAKING -> "ОТВЕЧАЮ"
        }
        micButton.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (state == KeshaState.IDLE) 23f else 30f,
        )
        auraBackground.setStroke(dp(context, 6), color)

        if (state == KeshaState.LISTENING) startPulse() else stopPulse()
    }

    /** Большой текст ответа. Прокрутка всегда начинается сначала. */
    fun showAnswer(text: String) {
        answerView.text = text
        scrollAnswerToTop()
    }

    /** Промежуточный результат распознавания — человек видит, что его слышат. */
    fun showListening(partial: String) {
        answerView.text = if (partial.isEmpty()) "Слушаю…" else partial
        scrollAnswerToTop()
    }

    /** Мелкая серая строка-подсказка. */
    fun showHint(text: String) {
        hintView.text = text
    }

    fun setRepeatEnabled(enabled: Boolean) {
        repeatButton.isEnabled = enabled
        repeatButton.alpha = if (enabled) 1f else 0.45f
    }

    fun setVoiceButtonVisible(visible: Boolean) {
        voiceButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun scrollAnswerToTop() {
        answerScroll.post { answerScroll.scrollTo(0, 0) }
    }

    private fun startPulse() {
        if (pulse != null) return
        auraView.visibility = View.VISIBLE
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 950L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.RESTART
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { running ->
            val progress = running.animatedValue as Float
            val scale = 1f + 0.12f * progress
            auraView.scaleX = scale
            auraView.scaleY = scale
            auraView.alpha = 0.9f * (1f - progress)
        }
        animator.start()
        pulse = animator
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        auraView.scaleX = 1f
        auraView.scaleY = 1f
        auraView.alpha = 1f
        auraView.visibility = View.INVISIBLE
    }

    private companion object {
        const val ANSWER_SP = 30f
        const val MIC_SIZE = 240
        const val HOLDER_SIZE = 280

        val COLOR_BACKGROUND = 0xFFFDF8F2.toInt()
        val COLOR_TEXT = 0xFF1B1B1B.toInt()
        val COLOR_TEXT_SOFT = 0xFF6E6E6E.toInt()
        val COLOR_IDLE = 0xFF8D9499.toInt()
        val COLOR_LISTENING = 0xFFE53935.toInt()
        val COLOR_THINKING = 0xFFF2A93B.toInt()
        val COLOR_SPEAKING = 0xFF2E9E4F.toInt()
        val COLOR_ON_DARK = 0xFFFFFFFF.toInt()
        val COLOR_ON_LIGHT = 0xFF3A2A00.toInt()
    }
}

private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    value.toFloat(),
    context.resources.displayMetrics,
).toInt()

private fun roundedRect(fill: Int, radiusPx: Int, strokePx: Int, strokeColor: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/**
 * Версия **установленного** APK в виде «сборка 1.3 (4)».
 *
 * Спрашиваем систему, а не берём константу из кода: смысл надписи именно в том,
 * чтобы показать, что реально стоит на телефоне. Если обновление не встало,
 * номер останется старым — это и есть сигнал.
 */
private fun installedVersion(context: Context): String = try {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val name = info.versionName ?: "?"
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    "сборка $name ($code)"
} catch (e: Exception) {
    "сборка неизвестна"
}
