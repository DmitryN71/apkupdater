package com.apkupdater.ui.component

import android.content.res.Configuration
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
