package cl.vintagemanager.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cl.vintagemanager.MainActivity
import cl.vintagemanager.R
import cl.vintagemanager.core.AlertPriority
import cl.vintagemanager.core.NotificationChannel as CoreChannel
import cl.vintagemanager.core.PushNotification
import cl.vintagemanager.core.PushPlan
import cl.vintagemanager.ui.navigation.routeFor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publica lo que decide el motor de alertas en Rust. Kotlin no filtra ni
 * prioriza: el tope diario, la deduplicación y el horario silencioso ya
 * vienen aplicados en el [PushPlan].
 */
@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val CHANNEL_CRITICAL = "alertas_criticas"
        const val CHANNEL_SUMMARY = "alertas_resumen"
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            context.getString(R.string.channel_critical_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_critical_desc) }
        val summary = NotificationChannel(
            CHANNEL_SUMMARY,
            context.getString(R.string.channel_summary_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_summary_desc) }
        nm.createNotificationChannels(listOf(critical, summary))
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(plan: PushPlan) {
        if (!canPost()) return
        val manager = NotificationManagerCompat.from(context)
        for (n in plan.notifications) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            ) {
                manager.notify(n.notificationId, build(n))
            }
        }
    }

    fun cancel(ids: List<Int>) {
        val manager = NotificationManagerCompat.from(context)
        ids.forEach { manager.cancel(it) }
    }

    private fun build(n: PushNotification): android.app.Notification {
        val channel = if (n.channel == CoreChannel.CRITICAS) CHANNEL_CRITICAL else CHANNEL_SUMMARY
        val route = routeFor(n.target)
        val open = PendingIntent.getActivity(
            context,
            n.notificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hanger)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(
                if (n.priority == AlertPriority.CRITICAL) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .addAction(0, "Ver detalle", open)

        if (n.lines.isNotEmpty()) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(n.title).setSummaryText(n.text)
            n.lines.take(6).forEach { style.addLine(it) }
            builder.setStyle(style)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
        }

        if (n.payableId != null || n.receivableId != null) {
            val paid = Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_MARK_PAID)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, n.notificationId)
            n.payableId?.let { paid.putExtra(NotificationActionReceiver.EXTRA_PAYABLE_ID, it) }
            n.receivableId?.let { paid.putExtra(NotificationActionReceiver.EXTRA_RECEIVABLE_ID, it) }
            builder.addAction(
                0,
                "Marcar como pagado",
                PendingIntent.getBroadcast(
                    context, n.notificationId * 10 + 1, paid,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        val snooze = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_SNOOZE)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, n.notificationId)
            .putExtra(NotificationActionReceiver.EXTRA_KEYS, n.alertKeys.toTypedArray())
        builder.addAction(
            0,
            "Posponer 1 día",
            PendingIntent.getBroadcast(
                context, n.notificationId * 10 + 2, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return builder.build()
    }
}
