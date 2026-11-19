package cl.vintagemanager.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.VintageCore
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.util.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel(protected val repo: VintageRepository) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    protected fun notify(msg: String) {
        _message.value = msg
    }

    /** Recarga los datos cada vez que el núcleo registra un cambio. */
    protected fun reloadOnChanges(load: suspend () -> Unit) {
        viewModelScope.launch { repo.changes.collect { load() } }
    }

    protected fun <T> mutate(
        success: String? = null,
        block: VintageCore.() -> T,
        onSuccess: (T) -> Unit = {},
    ): Job = viewModelScope.launch {
        repo.mutate(block)
            .onSuccess {
                success?.let(::notify)
                onSuccess(it)
            }
            .onFailure { notify(it.userMessage()) }
    }
}
