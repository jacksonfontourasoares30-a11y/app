package com.procam.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlin.math.roundToInt

/** Opção de balanço de branco disponível no aparelho. */
data class WbOption(val label: String, val mode: Int)

/**
 * O que a câmera do aparelho realmente suporta.
 * É lido quando a câmera abre, e a tela se adapta a isso
 * (assim o mesmo app funciona em celulares simples e top de linha).
 */
data class CameraCaps(
    val hardwareLevel: Int,
    val manualExposure: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    val manualFocus: Boolean,
    val minFocusDiopters: Float,
    val wbModes: List<WbOption>,
    val evSupported: Boolean,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    val rawSupported: Boolean,
) {
    val levelName: String
        get() = when (hardwareLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNA"
            else -> "?"
        }

    /** Converte um valor em EV (ex.: +1.0) para o índice que a câmera entende. */
    fun evIndexFor(ev: Float): Int =
        if (!evSupported || evStep <= 0f) 0 else (ev / evStep).roundToInt().coerceIn(evMin, evMax)

    fun wbLabel(mode: Int): String = wbModes.firstOrNull { it.mode == mode }?.label ?: "Auto"

    companion object {
        /** Exposição mais longa permitida no controle (1 segundo). */
        private const val MAX_EXPOSURE_NS = 1_000_000_000L

        private val ALL_WB = listOf(
            WbOption("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
            WbOption("Sol", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            WbOption("Nublado", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            WbOption("Sombra", CameraMetadata.CONTROL_AWB_MODE_SHADE),
            WbOption("Lâmpada", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
            WbOption("Fluoresc.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
        )

        @OptIn(ExperimentalCamera2Interop::class)
        fun from(camera: Camera): CameraCaps {
            val info = Camera2CameraInfo.from(camera.cameraInfo)

            val level = info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            val capabilities = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)
            val manualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val raw = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?: IntArray(0)
            val awbModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)

            val manualExposure = manualSensor && isoRange != null && expRange != null
            val expMin: Long = expRange?.lower ?: 100_000L
            val expMax: Long = if (expRange != null) minOf(expRange.upper, MAX_EXPOSURE_NS) else MAX_EXPOSURE_NS

            val exposureState = camera.cameraInfo.exposureState
            val evRange = exposureState.exposureCompensationRange

            return CameraCaps(
                hardwareLevel = level,
                manualExposure = manualExposure,
                isoMin = isoRange?.lower ?: 100,
                isoMax = isoRange?.upper ?: 3200,
                exposureMinNs = expMin,
                exposureMaxNs = maxOf(expMin, expMax),
                manualFocus = manualSensor && minFocus > 0f &&
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF),
                minFocusDiopters = minFocus,
                wbModes = ALL_WB.filter { awbModes.contains(it.mode) }.ifEmpty { listOf(ALL_WB[0]) },
                evSupported = exposureState.isExposureCompensationSupported,
                evMin = evRange.lower,
                evMax = evRange.upper,
                evStep = exposureState.exposureCompensationStep.toFloat(),
                rawSupported = raw,
            )
        }
    }
}
