package com.apkupdater.data.snack

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals

enum class SnackType { INFO, SUCCESS, ERROR }

class TextSnack(
    override val message: String,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    override val withDismissAction: Boolean = true,
    val type: SnackType = SnackType.INFO
): SnackbarVisuals
