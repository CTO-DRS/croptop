package app.croptop.feature.settings

import androidx.lifecycle.ViewModel
import app.croptop.core.ai.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AiModelsViewModel @Inject constructor(private val manager: ModelManager) : ViewModel() {
    val state = manager.state
    fun install(id: String) = manager.install(id)
    fun cancel(id: String) = manager.cancel(id)
    fun delete(id: String) = manager.delete(id)
}
