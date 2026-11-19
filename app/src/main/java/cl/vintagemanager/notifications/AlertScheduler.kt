package cl.vintagemanager.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val DAILY = "vintage_daily_alerts"
        private const val DEFERRED = "vintage_deferred_alerts"
        const val DEFAULT_MINUTE = 9 * 60
    }

    private fun delayUntil(minuteOfDay: Int): Duration {
        val now = LocalDateTime.now()
        var next = now.with(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** Revisión diaria a la hora configurada (por defecto 09:00). */
    fun scheduleDaily(minuteOfDay: Int = DEFAULT_MINUTE, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(minuteOfDay).toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Reintento único (fin del horario silencioso). */
    fun scheduleOnce(minuteOfDay: UInt) {
        val request = OneTimeWorkRequestBuilder<AlertWorker>()
            .setInitialDelay(delayUntil(minuteOfDay.toInt()).toMinutes() + 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DEFERRED, ExistingWorkPolicy.REPLACE, request)
    }

    /** Ejecuta la revisión ahora (botón "Probar alertas" en Ajustes). */
    fun runNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "vintage_alerts_now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AlertWorker>().build(),
        )
    }
}
