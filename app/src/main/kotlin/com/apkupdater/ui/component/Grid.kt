package com.apkupdater.ui.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyGridScope
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid

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
    if (text.isNotEmpty()) {
        MediumTitle(text, Modifier.align(Alignment.Center))
    }
    LazyColumn(Modifier.fillMaxSize()) {}
}

@Composable
fun TvInstalledGrid(scroll: Boolean = true, content: TvLazyGridScope.() -> Unit) = TvLazyVerticalGrid(
    columns = TvGridCells.Fixed(getTvNumColumns()),
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
