package com.procam.app

import android.content.Context
import android.hardware.camera2.CameraMetadata

/** Configuração atual da câmera (o que o usuário escolheu). */
data class ProSettings(
    val autoExposure: Boolean = true,
    val iso: Int = 100,
    val exposureNs: Long = 10_000_000L, // 1/100 s
    val autoFocus: Boolean = true,
    val focusDiopters: Float = 0f,       // 0 = infinito
    val wbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val evIndex: Int = 0,
) {
    /** Ajusta os valores para o que o aparelho suporta. */
    fun sanitized(c: CameraCaps): ProSettings {
        var s = this
        s = if (c.manualExposure) {
            s.copy(
                iso = s.iso.coerceIn(c.isoMin, c.isoMax),
                exposureNs = s.exposureNs.coerceIn(c.exposureMinNs, c.exposureMaxNs),
            )
        } else {
            s.copy(autoExposure = true)
        }
        s = if (c.manualFocus) {
            s.copy(focusDiopters = s.focusDiopters.coerceIn(0f, c.minFocusDiopters))
        } else {
            s.copy(autoFocus = true, focusDiopters = 0f)
        }
        if (c.wbModes.none { it.mode == s.wbMode }) {
            s = s.copy(wbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        s = s.copy(evIndex = if (c.evSupported) s.evIndex.coerceIn(c.evMin, c.evMax) else 0)
        return s
    }
}

/** Predefinições prontas: o app já abre configurado. */
object Presets {
    const val AUTO = "Auto"
    const val MANUAL = "Manual"
    val all = listOf(AUTO, "Noite", "Retrato", "Esporte", "Paisagem", MANUAL)

    fun build(name: String, c: CameraCaps): ProSettings {
        val s = when (name) {
            "Noite" ->
                if (c.manualExposure) ProSettings(autoExposure = false, iso = 1600, exposureNs = 66_666_667L) // 1/15 s
                else ProSettings(evIndex = c.evIndexFor(1.0f))
            "Retrato" -> ProSettings(evIndex = c.evIndexFor(0.3f))
            "Esporte" ->
                if (c.manualExposure) ProSettings(autoExposure = false, iso = 800, exposureNs = 1_000_000L) // 1/1000 s
                else ProSettings()
            "Paisagem" -> ProSettings(
                autoFocus = false,
                focusDiopters = 0f,
                wbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            )
            else -> ProSettings()
        }
        return s.sanitized(c)
    }

    fun description(name: String): String = when (name) {
        "Noite" -> "Noite: ISO alto e obturador lento. Apoie o celular."
        "Retrato" -> "Retrato: exposição levemente mais clara."
        "Esporte" -> "Esporte: obturador rápido (1/1000) para congelar movimento."
        "Paisagem" -> "Paisagem: foco no infinito e branco de luz do dia."
        MANUAL -> "Manual: seus ajustes salvos."
        else -> "Automático."
    }
}

/** Guarda os ajustes no aparelho para o app abrir do jeito que você deixou. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("procam", Context.MODE_PRIVATE)

    fun load(slot: String): ProSettings? {
        if (!prefs.contains("$slot.iso")) return null
        return ProSettings(
            autoExposure = prefs.getBoolean("$slot.ae", true),
            iso = prefs.getInt("$slot.iso", 100),
            exposureNs = prefs.getLong("$slot.exp", 10_000_000L),
            autoFocus = prefs.getBoolean("$slot.af", true),
            focusDiopters = prefs.getFloat("$slot.focus", 0f),
            wbMode = prefs.getInt("$slot.wb", CameraMetadata.CONTROL_AWB_MODE_AUTO),
            evIndex = prefs.getInt("$slot.ev", 0),
        )
    }

    fun save(slot: String, s: ProSettings) {
        prefs.edit()
            .putBoolean("$slot.ae", s.autoExposure)
            .putInt("$slot.iso", s.iso)
            .putLong("$slot.exp", s.exposureNs)
            .putBoolean("$slot.af", s.autoFocus)
            .putFloat("$slot.focus", s.focusDiopters)
            .putInt("$slot.wb", s.wbMode)
            .putInt("$slot.ev", s.evIndex)
            .apply()
    }

    var preset: String
        get() = prefs.getString("preset", Presets.AUTO) ?: Presets.AUTO
        set(value) = prefs.edit().putString("preset", value).apply()

    var gridOn: Boolean
        get() = prefs.getBoolean("grid", true)
        set(value) = prefs.edit().putBoolean("grid", value).apply()
}
