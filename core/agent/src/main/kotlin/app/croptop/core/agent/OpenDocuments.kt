package app.croptop.core.agent

import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.Engine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Documents currently open in any window. Editors register their handle and mark themselves
 * active on focus; agents act on the active one unless they name another.
 */
@Singleton
class OpenDocuments @Inject constructor(val engine: Engine) {
    private val _open = MutableStateFlow<List<DocumentHandle>>(emptyList())
    val open: StateFlow<List<DocumentHandle>> = _open
    private val _active = MutableStateFlow<DocumentHandle?>(null)
    val active: StateFlow<DocumentHandle?> = _active

    fun register(h: DocumentHandle) {
        _open.update { if (h in it) it else it + h }
        _active.value = h
    }

    fun unregister(h: DocumentHandle) {
        _open.update { it - h }
        if (_active.value == h) _active.value = _open.value.lastOrNull()
    }

    fun activate(h: DocumentHandle) {
        if (h in _open.value) _active.value = h
    }

    fun byId(id: Long): DocumentHandle? = _open.value.firstOrNull { it.id.value == id }
}
