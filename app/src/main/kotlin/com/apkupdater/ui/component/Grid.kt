package com.apkupdater.ui.component

import androidx.tv.foundation.lazy.grid.items

import androidx.compose.runtime.remember

import androidx.compose.runtime.key

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.IntrinsicSize

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyGridScope
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import com.apkupdater.R

@Composable
fun LoadingGrid() {
    TvShimmeringGrid()
}

@Composable
fun TvShimmeringGrid() = TvInstalledGrid(false) {
    items(16) {
        Box(Modifier.height(155.dp).shimmering(true))
    }
}

@Composable
fun EmptyGrid(
    text: String = ""
) = Box(Modifier.fillMaxSize()) {
    // Empty scrollable for pull-to-refresh support
    LazyColumn(Modifier.fillMaxSize()) {}
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp).alpha(0.4f),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        MediumText(
            if (text.isNotEmpty()) text else stringResource(R.string.all_up_to_date),
            Modifier.alpha(0.6f)
        )
    }
}

@Composable
fun TvInstalledGrid(
    scroll: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    content: TvLazyGridScope.() -> Unit
) = TvLazyVerticalGrid(
    columns = TvGridCells.Fixed(getTvNumColumns()),
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
    userScrollEnabled = scroll,
    modifier = Modifier.fillMaxSize()
)

@Composable
fun getTvNumColumns(): Int {
    return if(LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT)
        1
    else
        2
}

/**
 * The update and search lists: cards in rows, every card in a row as tall as the tallest.
 *
 * A lazy grid measures each item on its own and places it at the top of its line, so when one
 * card of a landscape or TV pair is opened, its neighbour keeps its old height. That is ugly,
 * and on a D-pad it is worse than ugly: directional focus search is geometric, and with the two
 * action rows at different heights DOWN from one card's Update lands somewhere unexpected.
 * Reported by Dmitry from his TV.
 *
 * So the grid has ONE column and each item is a whole row: a Row measured at its max intrinsic
 * height, with every card told to fill it. The card puts its action row at the bottom (see the
 * Spacer before the divider in TvUpdateItem), so Skip / Update line up across the row and
 * LEFT / RIGHT / DOWN go where the eye expects. The container is still TvLazyVerticalGrid,
 * for its pivot scrolling and the edge-focus behaviour that took builds 105–137 to tame — it
 * simply holds rows now instead of cards. In portrait a row is one card, and nothing changes.
 *
 * Each card sits in its own key() so a row that re-chunks after a Skip does not hand one card's
 * saved state to whichever card slid into its slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> TvEqualRows(
    items: List<T>,
    itemKey: (T) -> Any,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    card: @Composable (item: T, modifier: Modifier) -> Unit
) {
    val columns = getTvNumColumns()
    val rows = remember(items, columns) { items.chunked(columns) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    TvLazyVerticalGrid(
        columns = TvGridCells.Fixed(1),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height }
    ) {
        items(rows, key = { row -> itemKey(row.first()) }) { row ->
            val wholeRow = remember { WholeRowInView { viewportHeight } }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .onSizeChanged { wholeRow.size = it }
                    .bringIntoViewResponder(wholeRow),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    key(itemKey(item)) {
                        card(item, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                // A short last row keeps its column width rather than stretching one card wide.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Makes a focus move inside a row scroll the grid to the ROW, not to the button that took focus.
 *
 * TvLazyVerticalGrid pivots: whatever asks to be brought into view has its top edge put 30% of
 * the way down the viewport, even when it is already on screen. A card's buttons sit at its
 * bottom, so focusing one dragged the card up under the top bar by most of its height — right
 * after a check, the first row came up cut in half with the list already scrolled. Reported by
 * Dmitry from his TV, with the Open button exactly 30% down the list (238 of 792 px).
 *
 * With the whole row as the target, the same pivot does the right thing: the grid cannot scroll
 * above its start, so the first row stays put; a row further down lands at the pivot, or is
 * bottom-aligned when it is too tall for that; and moving between the chip and the buttons of
 * one card no longer shifts the list at all. A row taller than the viewport cannot be shown
 * whole, so there the focused element alone is asked for, as before, and it stays in sight.
 */
@OptIn(ExperimentalFoundationApi::class)
private class WholeRowInView(private val viewportHeight: () -> Int) : BringIntoViewResponder {

    var size = IntSize.Zero

    override fun calculateRectForParent(localRect: Rect): Rect {
        val fits = size.height > 0 && size.height <= viewportHeight()
        return if (fits) Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) else localRect
    }

    // The row does not scroll by itself; the grid above it does the scrolling.
    override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit

}
