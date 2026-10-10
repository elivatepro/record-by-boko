// Generated from packages/design-tokens/tokens.json by generate.mjs. Do not edit.
package com.boko.roudio.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Raw colours from the Paper file. Prefer [RoudioColors] roles in UI code. */
object RoudioPalette {
    /** Light ground */
    val Paper = Color(0xFFFFFFFF)
    /** Light raised surface, chips, round buttons */
    val Fog = Color(0xFFF1F4F8)
    /** Light row dividers */
    val Hairline = Color(0xFFE3E7EE)
    /** Light secondary text (6.1:1 on paper) */
    val Muted = Color(0xFF56627A)
    /** Light ink, primary fills, recorded arcs */
    val Navy = Color(0xFF0E2240)
    /** Signal: now, still on Plaud, unread. Never text on white. */
    val Buoy = Color(0xFFFFB800)
    /** Text-safe buoy for light mode (pending words) */
    val BuoyInk = Color(0xFF8A5A00)
    /** Light failure text and icons */
    val Alert = Color(0xFFB3261E)
    /** Light playing segment / not-enough answer tint */
    val WarningTint = Color(0xFFFFF6DB)
    /** Light warning tint border */
    val WarningRim = Color(0xFFF2D27A)
    /** Dark ground */
    val Night = Color(0xFF07111D)
    /** Dark sheets */
    val NightSheet = Color(0xFF0B1726)
    /** Dark sheet top rim */
    val NightSheetRim = Color(0xFF22344F)
    /** Dark raised surface */
    val NightRaised = Color(0xFF111F33)
    /** Dark hero card (digest, player) */
    val NightCard = Color(0xFF16294A)
    /** Dark tiles inside the hero card */
    val NightCardTile = Color(0xFF1F355C)
    /** Dark dividers */
    val NightHairline = Color(0xFF1B2A3F)
    /** Dark secondary text */
    val NightMuted = Color(0xFF9AA8BE)
    /** Dark future/disabled numbers (below 4.5:1 on purpose) */
    val NightDisabled = Color(0xFF4E5D74)
    /** Dark primary text and fills */
    val NightInk = Color(0xFFE8EEF7)
    /** Dark signal */
    val NightBuoy = Color(0xFFFFC433)
    /** Dark failure text and icons */
    val NightAlert = Color(0xFFFF8A80)
    /** Dark failure icon tile */
    val NightAlertTile = Color(0xFF3A1A1F)
    /** Dark playing segment / not-enough answer tint */
    val NightWarningTint = Color(0xFF2A2410)
    /** Dark warning tint border */
    val NightWarningRim = Color(0xFF5C4A14)
}

/** Semantic colour roles. One instance each for light and dark. */
@Immutable
data class RoudioColors(
    val ground: Color,
    val sheet: Color,
    val sheetRim: Color,
    val raised: Color,
    val heroCard: Color,
    val onHeroCard: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val primary: Color,
    val onPrimary: Color,
    val buoy: Color,
    val onBuoy: Color,
    val buoyText: Color,
    val alert: Color,
    val warningTint: Color,
    val warningRim: Color,
)

val LightRoudioColors = RoudioColors(
    ground = RoudioPalette.Paper,
    sheet = RoudioPalette.Paper,
    sheetRim = RoudioPalette.Hairline,
    raised = RoudioPalette.Fog,
    heroCard = RoudioPalette.Navy,
    onHeroCard = RoudioPalette.Paper,
    ink = RoudioPalette.Navy,
    muted = RoudioPalette.Muted,
    hairline = RoudioPalette.Hairline,
    primary = RoudioPalette.Navy,
    onPrimary = RoudioPalette.Paper,
    buoy = RoudioPalette.Buoy,
    onBuoy = RoudioPalette.Navy,
    buoyText = RoudioPalette.BuoyInk,
    alert = RoudioPalette.Alert,
    warningTint = RoudioPalette.WarningTint,
    warningRim = RoudioPalette.WarningRim,
)

val DarkRoudioColors = RoudioColors(
    ground = RoudioPalette.Night,
    sheet = RoudioPalette.NightSheet,
    sheetRim = RoudioPalette.NightSheetRim,
    raised = RoudioPalette.NightRaised,
    heroCard = RoudioPalette.NightCard,
    onHeroCard = RoudioPalette.NightInk,
    ink = RoudioPalette.NightInk,
    muted = RoudioPalette.NightMuted,
    hairline = RoudioPalette.NightHairline,
    primary = RoudioPalette.NightInk,
    onPrimary = RoudioPalette.Night,
    buoy = RoudioPalette.NightBuoy,
    onBuoy = RoudioPalette.Night,
    buoyText = RoudioPalette.NightBuoy,
    alert = RoudioPalette.NightAlert,
    warningTint = RoudioPalette.NightWarningTint,
    warningRim = RoudioPalette.NightWarningRim,
)

/** Type scale: font size and line height in sp. */
object RoudioTypeScale {
    val DisplaySize = 34.sp
    val DisplayLineHeight = 40.sp
    val TitleSize = 26.sp
    val TitleLineHeight = 32.sp
    val SectionSize = 20.sp
    val SectionLineHeight = 26.sp
    val TranscriptSize = 17.sp
    val TranscriptLineHeight = 28.sp
    val BodySize = 16.sp
    val BodyLineHeight = 24.sp
    val SmallSize = 14.sp
    val SmallLineHeight = 20.sp
    val LabelSize = 12.sp
    val LabelLineHeight = 16.sp
}

object RoudioRadius {
    val Chip = 18.dp
    val Card = 24.dp
    val Sheet = 28.dp
    val Pill = 999.dp
}
