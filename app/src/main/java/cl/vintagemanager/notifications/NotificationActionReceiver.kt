package cl.vintagemanager.notifications

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Acciones directas desde la notificación: "Marcar como pagado" y "Posponer 1 día". */
@AndroidEntryPoint
class NotificationActionReceiver : HiltBroadcastReceiver() {

    companion object {
        const val ACTION_MARK_PAID = "cl.vintagemanager.action.MARK_PAID"
        const val ACTION_SNOOZE = "cl.vintagemanager.action.SNOOZE"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_PAYABLE_ID = "payable_id"
        const val EXTRA_RECEIVABLE_ID = "receivable_id"
        const val EXTRA_KEYS = "alert_keys"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Inject lateinit var repository: VintageRepository

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        scope.launch {
            try {
                val result: Result<Unit> = when (intent.action) {
                    ACTION_MARK_PAID -> {
                        val payable = intent.getLongExtra(EXTRA_PAYABLE_ID, -1)
                        val receivable = intent.getLongExtra(EXTRA_RECEIVABLE_ID, -1)
                        repository.mutate {
                            if (payable > 0) markPayablePaid(payable, today(), PaymentMethod.TRANSFER)
                            if (receivable > 0) markReceivablePaid(receivable, today(), PaymentMethod.TRANSFER)
                            Unit
                        }
                    }
                    ACTION_SNOOZE -> {
                        val keys = intent.getStringArrayExtra(EXTRA_KEYS)?.toList().orEmpty()
                        repository.mutate { snoozeAlerts(keys, today().plusDays(1)) }
                    }
                    else -> Result.success(Unit)
                }
                if (notificationId >= 0) NotificationManagerCompat.from(context).cancel(notificationId)
                result.exceptionOrNull()?.let { e ->
                    android.os.Handler(context.mainLooper).post {
                        Toast.makeText(context, e.userMessage(), Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
