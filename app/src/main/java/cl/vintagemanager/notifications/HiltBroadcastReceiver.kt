package cl.vintagemanager.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.CallSuper

/**
 * Hilt inyecta las dependencias de un BroadcastReceiver dentro de
 * `super.onReceive()`. Como el método es abstracto en BroadcastReceiver,
 * esta clase base entrega una implementación vacía que se puede invocar.
 */
abstract class HiltBroadcastReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) {
    }
}
