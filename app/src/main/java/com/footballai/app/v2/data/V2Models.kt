package com.footballai.app.v2.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * DTOs for the football-ai-data v2 contract (see DATA_CONTRACT.md in
 * football-ai-data). Only the fields the app shell consumes are declared;
 * the parser ignores everything else (per-match `details`, `history.json`
 * enrichment) — those arrive in later slices.
 */

/**
 * FotMob is inconsistent about team ids: league payloads carry them as JSON
 * numbers, some fixture lists as strings. Both decode to the same String.
 */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive) element.content else element.toString()
    }
}

@Serializable
data class V2TeamDto(
    @Serializable(with = FlexibleStringSerializer::class) val teamId: String,
    val name: String,
    val shortName: String? = null,
) {
    val displayName: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class V2FixtureDto(
    val id: Long,
    val kickoffUtc: String,
    val status: String,
    val round: String? = null,
    val home: V2TeamDto,
    val away: V2TeamDto,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val minute: String? = null,
)

@Serializable
data class V2LeagueInfoDto(
    val slug: String,
    val fotmobId: Int = 0,
    val name: String = "",
    val country: String = "",
    val season: String = "",
)

@Serializable
data class V2WindowDto(val from: String = "", val to: String = "")

@Serializable
data class V2FeaturesDto(
    val xg: Boolean = false,
    val availability: Boolean = false,
    val lineups: Boolean = false,
    val formationsPublished: Boolean = false,
)

@Serializable
data class V2TableRowDto(
    val position: Int,
    @Serializable(with = FlexibleStringSerializer::class) val teamId: String,
    val name: String,
    val played: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val goalDifference: Int = 0,
    val points: Int = 0,
)

@Serializable
data class V2BundleDto(
    val contractVersion: Int = 2,
    val league: V2LeagueInfoDto,
    val generatedAt: String,
    val window: V2WindowDto? = null,
    val features: V2FeaturesDto? = null,
    val fixtures: List<V2FixtureDto> = emptyList(),
    val table: List<V2TableRowDto> = emptyList(),
)

@Serializable
data class V2LiveMatchDto(
    val id: Long,
    val leagueSlug: String,
    val kickoffUtc: String,
    val status: String,
    val minute: String? = null,
    val home: V2TeamDto,
    val away: V2TeamDto,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
)

@Serializable
data class V2LiveDto(
    val contractVersion: Int = 2,
    val generatedAt: String,
    val matches: List<V2LiveMatchDto> = emptyList(),
)
