package com.footballai.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The spacing scale the screens share.
 *
 * Before this, every screen picked its own padding (14, 16, 12, 18, 22 …), which is why
 * the app read as assembled rather than designed: nothing lined up between one card and
 * the next. Four-pixel steps in a small set of names are enough for every layout, and a
 * reader sees the rhythm even if they could not name it.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal page margin used by every list and header. */
    val page = 16.dp

    /** Gap between two cards in a list. */
    val cardGap = 12.dp
}

/** Corner radii. One card, one sheet, one chip — not five of each. */
object Radii {
    val chip = 999.dp
    val small = 12.dp
    val card = 22.dp
    val sheet = 28.dp

    val cardShape = RoundedCornerShape(card)
    val smallShape = RoundedCornerShape(small)
    val sheetShape = RoundedCornerShape(topStart = sheet, topEnd = sheet)
}

/** Sizes for the one element every football screen has: the score. */
object ScoreSizes {
    val hero = 34.dp
}
