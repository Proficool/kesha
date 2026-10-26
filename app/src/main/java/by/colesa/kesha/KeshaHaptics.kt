package by.colesa.kesha

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Короткая вибрация «оно меня слышит» — в начале и в конце записи.
 *
 * Разрешение VIBRATE объявлено в манифесте: это normal-разрешение, диалога
 * пользователю не показывают, веса в APK не имеет. Если разрешения или самого
 * вибромотора нет — откатываемся на штатный тактильный отклик View.
 */
internal class KeshaHaptics(private val context: Context, private val anchor: View) {

    private val vibrator: Vibrator? = resolveVibrator()

    fun tap() {
        val device = vibrator
        if (device != null) {
            try {
                if (!device.hasVibrator()) throw IllegalStateException("no vibrator")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.vibrate(
                        VibrationEffect.createOneShot(DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrate(DURATION_MS)
                }
                return
            } catch (e: Exception) {
                // Тихо уходим на запасной вариант.
            }
        }
        anchor.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun resolveVibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.VIBRATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                (manager as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val DURATION_MS = 45L
    }
}
