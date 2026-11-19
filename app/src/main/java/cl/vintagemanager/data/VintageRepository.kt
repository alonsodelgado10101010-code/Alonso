package cl.vintagemanager.data

import cl.vintagemanager.core.VintageCore
import cl.vintagemanager.notifications.NotificationHelper
import cl.vintagemanager.util.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envoltorio fino sobre el núcleo Rust. No contiene lógica de negocio:
 * solo traslada las llamadas a Dispatchers.IO y avisa a las pantallas cuando
 * algo cambió, para que vuelvan a pedir sus datos al núcleo.
 */
@Singleton
class VintageRepository @Inject constructor(
    private val core: VintageCore,
    private val notifications: NotificationHelper,
) {
    private val _changes = MutableStateFlow(0L)

    /** Se incrementa con cada escritura exitosa. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    suspend fun <T> query(block: VintageCore.() -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching { core.block() }
    }

    suspend fun <T> mutate(block: VintageCore.() -> T): Result<T> = withContext(Dispatchers.IO) {
        val result = runCatching { core.block() }
        if (result.isSuccess) {
            _changes.update { it + 1 }
            // Al resolver la causa (pagar, cobrar, rebajar) la notificación se retira sola.
            runCatching { notifications.cancel(core.staleNotificationIds(today())) }
        }
        result
    }
}
