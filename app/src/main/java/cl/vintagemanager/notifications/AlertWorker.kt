package cl.vintagemanager.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.util.minuteOfDay
import cl.vintagemanager.util.today
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Revisión diaria: llama a `evaluate_alerts` del núcleo Rust (que aplica
 * prioridades, deduplicación, horario silencioso y el tope de 3 diarias) y
 * solo publica el resultado.
 */
@HiltWorker
class AlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: VintageRepository,
    private val notifications: NotificationHelper,
    private val scheduler: AlertScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val plan = repository.mutate { evaluateAlerts(today(), minuteOfDay()) }
            .getOrElse { return Result.retry() }
        // En horario silencioso las alertas quedan encoladas hasta que termine.
        plan.deferredUntilMinute?.let { scheduler.scheduleOnce(it) }
        notifications.post(plan)
        return Result.success()
    }
}
