// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Custom profiles: a built-in zperfd mode plus overrides, stored and validated
 * by zperfd (`zperfd custom list|put|delete`) and applied with `zperfd set`
 * like any other mode. The app only edits a draft; the checks here mirror the
 * engine's limits so mistakes are caught before crossing the root boundary,
 * but zperfd's answer is the one that counts.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import androidx.annotation.StringRes
import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Mode values as zperfd reports them: section ("cpu") -> field -> value. */
typealias ModeValues = Map<String, Map<String, JsonElement>>

/** A field's value as editable text; null when unset. Lists are comma separated. */
fun ModeValues.text(key: String): String? {
    val (section, field) = key.split('.', limit = 2).takeIf { it.size == 2 } ?: return null
    return when (val v = this[section]?.get(field)) {
        null, JsonNull -> null
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        is JsonPrimitive -> v.content
        else -> null
    }
}

@Serializable
data class CustomBase(val id: String = "", val values: ModeValues = emptyMap())

@Serializable
data class CustomProfile(
    val id: String = "",
    val label: String = "",
    val base: String = "",
    /** False when the entry no longer resolves (e.g. its base left the device profile). */
    val valid: Boolean = false,
    val error: String? = null,
    val overrides: ModeValues = emptyMap(),
    /** The merged mode zperfd applies; null when invalid. */
    val values: ModeValues? = null,
)

@Serializable
data class CustomListResult(
    val ok: Boolean = false,
    val max: Int = 0,
    val profile: String = "",
    val bases: List<CustomBase> = emptyList(),
    val profiles: List<CustomProfile> = emptyList(),
)

@Serializable
data class CustomWriteResult(
    val ok: Boolean = false,
    val id: String = "",
    val kind: String? = null,
    val error: String? = null,
)

enum class FieldKind { INT, FREQ, LIST }

data class CustomField(
    val key: String,
    @StringRes val title: Int,
    val kind: FieldKind,
    val range: LongRange? = null,
) {
    /** Input hint for the field kind; null for plain numbers. */
    @get:StringRes val hint: Int?
        get() = when (kind) {
            FieldKind.FREQ -> R.string.custom_hint_freq
            FieldKind.LIST -> R.string.custom_hint_list
            FieldKind.INT -> null
        }

    val section: String get() = key.substringBefore('.')
    val name: String get() = key.substringAfter('.')
}

object CustomFields {
    private val U32 = 0L..4_294_967_295L
    private val I32 = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    /** Every field of a zperfd mode, with the engine's validation range where it has one. */
    val all: List<CustomField> = listOf(
        CustomField("cpu.min_freq", R.string.custom_field_cpu_min_freq, FieldKind.FREQ),
        CustomField("cpu.max_freq", R.string.custom_field_cpu_max_freq, FieldKind.FREQ),
        CustomField("cpu.min_perf_pct", R.string.custom_field_cpu_min_perf_pct, FieldKind.INT, 0L..100L),
        CustomField("cpu.max_perf_pct", R.string.custom_field_cpu_max_perf_pct, FieldKind.INT, 0L..100L),
        CustomField("cpu.governor", R.string.custom_field_cpu_governor, FieldKind.LIST),
        CustomField("cpu.uclamp_min_pct", R.string.custom_field_cpu_uclamp_min, FieldKind.INT, 0L..100L),
        CustomField("cpu.uclamp_max_pct", R.string.custom_field_cpu_uclamp_max, FieldKind.INT, 0L..100L),
        CustomField("cpu.input_boost_pct", R.string.custom_field_cpu_input_boost_pct, FieldKind.INT, 0L..100L),
        CustomField("cpu.input_boost_ms", R.string.custom_field_cpu_input_boost_ms, FieldKind.INT, 0L..10_000L),
        CustomField("cpu.sched_boost", R.string.custom_field_cpu_sched_boost, FieldKind.INT, -100L..100L),
        CustomField("gpu.min_freq", R.string.custom_field_gpu_min_freq, FieldKind.FREQ),
        CustomField("gpu.max_freq", R.string.custom_field_gpu_max_freq, FieldKind.FREQ),
        CustomField("gpu.min_perf_pct", R.string.custom_field_gpu_min_perf_pct, FieldKind.INT, 0L..100L),
        CustomField("gpu.max_perf_pct", R.string.custom_field_gpu_max_perf_pct, FieldKind.INT, 0L..100L),
        CustomField("gpu.governor", R.string.custom_field_gpu_governor, FieldKind.LIST),
        CustomField("mem.swappiness", R.string.custom_field_mem_swappiness, FieldKind.INT, 0L..200L),
        CustomField("mem.vfs_cache_pressure", R.string.custom_field_mem_vfs_cache_pressure, FieldKind.INT, 0L..1000L),
        CustomField("mem.watermark_scale_factor", R.string.custom_field_mem_watermark_scale_factor, FieldKind.INT, U32),
        CustomField("mem.page_cluster", R.string.custom_field_mem_page_cluster, FieldKind.INT, I32),
        CustomField("mem.extra_free_kbytes", R.string.custom_field_mem_extra_free_kbytes, FieldKind.INT, U32),
        CustomField("io.scheduler", R.string.custom_field_io_scheduler, FieldKind.LIST),
        CustomField("io.read_ahead_kb", R.string.custom_field_io_read_ahead_kb, FieldKind.INT, 0L..4096L),
        CustomField("io.nr_requests", R.string.custom_field_io_nr_requests, FieldKind.INT, 1L..4096L),
    )

    val sections: List<Pair<String, Int>> = listOf(
        "cpu" to R.string.custom_section_cpu, "gpu" to R.string.custom_section_gpu,
        "mem" to R.string.custom_section_mem, "io" to R.string.custom_section_io,
    )

    fun byKey(key: String): CustomField? = all.firstOrNull { it.key == key }
}

private val CUSTOM_ID = Regex("custom-[a-z0-9_-]{1,24}")

fun isCustomId(id: String): Boolean = CUSTOM_ID.matches(id)

/** A stable id from the label ("Game Dingin" -> "custom-game-dingin"), unique among [existing]. */
fun newCustomId(label: String, existing: Set<String>): String {
    val slug = label.lowercase()
        .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '-' }
        .joinToString("")
        .split('-').filter { it.isNotEmpty() }.joinToString("-")
        .take(20).trim('-')
        .ifEmpty { "profil" }
    var id = "custom-$slug"
    var n = 2
    while (id in existing) id = "custom-$slug-${n++}"
    return id
}

/** An editable custom profile. [values] holds override text by field key; blank follows the base. */
data class CustomDraft(
    val id: String,
    val label: String,
    val base: String,
    val values: Map<String, String> = emptyMap(),
    val isNew: Boolean = true,
) {
    fun value(key: String): String = values[key].orEmpty()
    fun with(key: String, text: String): CustomDraft = copy(values = values + (key to text))

    private val overrides: List<Pair<CustomField, String>>
        get() = CustomFields.all.mapNotNull { f -> values[f.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { f to it } }

    /** Number of fields that differ from the base. */
    val overrideCount: Int get() = overrides.size

    /** The first problem a user can fix, or null. zperfd re-validates everything. */
    fun validate(): UiText? {
        if (!isCustomId(id)) return uiText(R.string.custom_err_id)
        val n = label.trim().length
        if (n == 0 || label.length > 32 || label.any { it.isISOControl() }) return uiText(R.string.custom_err_label)
        if (base.isBlank()) return uiText(R.string.custom_err_base)
        for ((f, text) in overrides) {
            val title = uiText(f.title)
            when (f.kind) {
                FieldKind.INT -> {
                    val v = text.toLongOrNull() ?: return uiText(R.string.custom_err_integer, title)
                    val r = f.range
                    if (r != null && v !in r) return uiText(R.string.custom_err_range, title, r.first, r.last)
                }
                FieldKind.FREQ ->
                    if (text.length > 32 || !FREQ.matches(text)) return uiText(R.string.custom_err_freq, title)
                FieldKind.LIST -> {
                    val items = listItems(text)
                    if (items.isEmpty() || items.size > 16 || items.any { it.length > 64 || '/' in it || '\\' in it }) {
                        return uiText(R.string.custom_err_list, title)
                    }
                }
            }
        }
        val uMin = values["cpu.uclamp_min_pct"]?.trim()?.toLongOrNull()
        val uMax = values["cpu.uclamp_max_pct"]?.trim()?.toLongOrNull()
        if (uMin != null && uMax != null && uMin > uMax) return uiText(R.string.custom_err_uclamp)
        return null
    }

    /** The definition `zperfd custom put` reads. Call only after [validate] returned null. */
    fun toToml(): String = buildString {
        append("label = ").append(tomlString(label.trim())).append('\n')
        append("base = ").append(tomlString(base)).append('\n')
        for ((section, _) in CustomFields.sections) {
            val fields = overrides.filter { it.first.section == section }
            if (fields.isEmpty()) continue
            append("\n[").append(section).append("]\n")
            for ((f, text) in fields) {
                append(f.name).append(" = ")
                when (f.kind) {
                    FieldKind.INT -> append(text.toLong())
                    FieldKind.FREQ -> append(tomlString(text))
                    FieldKind.LIST -> append(listItems(text).joinToString(", ", "[", "]") { tomlString(it) })
                }
                append('\n')
            }
        }
    }

    companion object {
        private val FREQ = Regex(
            "(?i)min|max|\\d+(\\.\\d+)?\\s*%|-?\\s*\\d+(\\.\\d+)?\\s*(ghz|mhz|khz)?",
        )

        fun listItems(text: String): List<String> =
            text.split(',', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun fromProfile(p: CustomProfile): CustomDraft = CustomDraft(
            id = p.id,
            label = p.label,
            base = p.base,
            values = CustomFields.all.mapNotNull { f -> p.overrides.text(f.key)?.let { f.key to it } }.toMap(),
            isNew = false,
        )

        /** TOML basic string: quotes, backslashes and control characters escaped. */
        fun tomlString(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.isISOControl() -> append("\\u%04X".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}
