package com.msp1974.vacompanion.wakeword.microwakeword

import android.content.res.AssetManager
import com.msp1974.vacompanion.wakeword.microwakeword.models.WakeWord
import com.msp1974.vacompanion.wakeword.microwakeword.providers.AssetWakeWordProvider
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Builds a map of model id → author-recommended threshold as an integer percentage (0–100),
 * from bundled wake-word and stop-word JSON under [AssetWakeWordProvider.DEFAULT_WAKE_WORD_PATH]
 * and `stopWords` (same source the runtime uses).
 */
object MicroWakeWordAuthorDefaults {

    const val CAPABILITIES_KEY = "microwakeword_author_defaults"

    @OptIn(ExperimentalSerializationApi::class)
    fun buildJsonObject(assets: AssetManager): JsonObject {
        return buildJsonObject {
            putAuthorDefaultsFromPath(assets, AssetWakeWordProvider.DEFAULT_WAKE_WORD_PATH)
            putAuthorDefaultsFromPath(assets, "stopWords")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun JsonObjectBuilder.putAuthorDefaultsFromPath(assets: AssetManager, relPath: String) {
        val names = assets.list(relPath) ?: return
        for (asset in names.filter { it.endsWith(".json") }) {
            runCatching {
                val id = asset.substring(0, asset.length - ".json".length)
                assets.open("$relPath/$asset").use { stream ->
                    val wakeWord = Json.decodeFromStream<WakeWord>(stream)
                    val pct =
                        (wakeWord.micro.probability_cutoff * 100f).roundToInt().coerceIn(0, 100)
                    put(id, pct)
                }
            }
        }
    }
}
