package com.jarvis.pineapple.model

/**
 * 首次运行模型安装向导。无本地模型时展示向导，用户确认后才下载。
 */
data class OnboardingState(
    val needed: Boolean = false,
    val selectedModelId: String = "",
    val error: String? = null,
    val downloading: Boolean = false
)

class OnboardingGate(
    private val hasLocalModel: () -> Boolean,
    private val autoDownloadEnabled: () -> Boolean = { false }
) {
    fun shouldShowWizard(): Boolean = !hasLocalModel()

    fun canStartDownload(userConfirmed: Boolean): Boolean =
        userConfirmed && !autoDownloadEnabled() || userConfirmed

    fun allowNetworkDownload(userConfirmed: Boolean): Boolean {
        if (!userConfirmed) return false
        return true
    }

    fun timeoutError(): String = DOWNLOAD_TIMEOUT

    fun failureError(cause: String?): String =
        if (cause.isNullOrBlank()) DOWNLOAD_FAILED else "模型下载失败：$cause"

    companion object {
        const val DOWNLOAD_TIMEOUT = "模型下载超时"
        const val DOWNLOAD_FAILED = "模型下载失败"
        const val TIMEOUT_MS = 30_000L
        const val PREFS = "jarvis_onboarding"
        const val KEY_CONFIRMED = "model_download_confirmed"
        const val KEY_SKIPPED = "model_wizard_skipped"
    }
}
