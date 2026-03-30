package com.msp1974.vacompanion.wakeword.microwakeword

import android.content.res.AssetManager
import com.msp1974.vacompanion.wakeword.microwakeword.models.WakeWord
import com.msp1974.vacompanion.wakeword.microwakeword.providers.AssetWakeWordProvider
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Builds a map of wake word id → author-recommended threshold as an integer percentage (0–100),
 * derived from each bundled model's [WakeWord.micro] metadata (same source the runtime uses).
 */
object MicroWakeWordAuthorDefaults {

    const val CAPABILITIES_KEY = "microwakeword_author_defaults"

    @OptIn(ExperimentalSerializationApi::class)
    fun buildJsonObject(assets: AssetManager): JsonObject {
        val path = AssetWakeWordProvider.DEFAULT_WAKE_WORD_PATH
        val names = assets.list(path) ?: return buildJsonObject { }
        return buildJsonObject {
            for (asset in names.filter { it.endsWith(".json") }) {
                runCatching {
                    val id = asset.substring(0, asset.length - ".json".length)
                    assets.open("$path/$asset").use { stream ->
                        val wakeWord = Json.decodeFromStream<WakeWord>(stream)
                        val pct =
                            (wakeWord.micro.probability_cutoff * 100f).roundToInt().coerceIn(0, 100)
                        put(id, pct)
                    }
                }
            }
        }
    }
}
