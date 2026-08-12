package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.api.ModelProfileDto

data class ModelProfilesUiState(
    val loading: Boolean = true,
    val switchingProfileId: String = "",
    val profiles: List<ModelProfileDto> = emptyList(),
    val activeProfileId: String = "",
    val message: String = "",
    val error: String = "",
)

class ModelProfilesViewModel(
    private val repository: ModelSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelProfilesUiState())
    val state: StateFlow<ModelProfilesUiState> = mutableState.asStateFlow()
    private var hasResumed = false

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(loading = current.profiles.isEmpty(), error = "")
            }
            try {
                apply(repository.getModelSettings())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(loading = false, error = error.message ?: "模型档案读取失败。") }
            }
        }
    }

    fun refreshWhenResumed() {
        if (!hasResumed) {
            hasResumed = true
            return
        }
        load()
    }

    fun activate(profile: ModelProfileDto) {
        if (!profile.configured || profile.profileId == state.value.activeProfileId || state.value.switchingProfileId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(switchingProfileId = profile.profileId, error = "", message = "") }
            try {
                apply(repository.activateModelProfile(profile.profileId), "已切换当前模型。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(switchingProfileId = "", error = error.message ?: "模型切换失败。")
                }
            }
        }
    }

    private fun apply(settings: top.wkbin.zaomeng.data.api.ModelSettingsDto, message: String = "") {
        mutableState.value = ModelProfilesUiState(
            loading = false,
            profiles = settings.profiles,
            activeProfileId = settings.activeProfileId,
            message = message,
        )
    }
}
