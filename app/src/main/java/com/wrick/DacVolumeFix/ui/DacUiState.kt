package com.wrick.DacVolumeFix.ui

import androidx.compose.runtime.Immutable

/**
 * Represents the distinct UI states of the DAC controller.
 * Marked @Immutable so the Compose runtime guarantees skip-recomposition
 * for stable inputs, eliminating frame drops and stutter.
 */
@Immutable
sealed interface DacUiState {
    @Immutable
    data object Idle : DacUiState

    @Immutable
    data class DacConnected(
        val deviceName: String,
        val vendorIdHex: String,
        val productIdHex: String,
        val targetVolumeDb: Int,
        val targetVolumeFormatted: String,
        val idsFormatted: String
    ) : DacUiState

    @Immutable
    data class UnlockSuccess(
        val deviceName: String,
        val volumeDb: Int,
        val volumeFormatted: String,
        val statusMessage: String
    ) : DacUiState

    @Immutable
    data class UnlockFailed(
        val deviceName: String,
        val reason: String
    ) : DacUiState
}
