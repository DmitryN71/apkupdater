package com.apkupdater.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.apkupdater.R
import com.apkupdater.util.getAppIcon

@Composable
private fun BaseLoadingImage(
    request: ImageRequest,
    modifier: Modifier,
    color: Color = Color.Transparent,
    // Inside the caller's size, so it costs 20 dp of it whatever that size is. Fine at 100 dp,
    // where 80 dp of icon is left; ruinous at 44, where 24 dp is — which is why the compact
    // card's icons came out looking half the size they were asked for. Callers working small
    // pass a smaller figure.
    padding: Dp = 10.dp
) = AsyncImage(
    model = request,
    contentDescription = stringResource(R.string.app_cd),
    modifier = modifier
        .padding(padding)
        .clip(RoundedCornerShape(8.dp))
        .background(color),
    contentScale = ContentScale.Fit,
    error = painterResource(R.drawable.ic_root),
    placeholder = painterResource(R.drawable.ic_empty)
)

@Composable
fun LoadingImage(
    uri: Uri,
    modifier: Modifier = Modifier.height(120.dp).fillMaxSize(),
    crossfade: Boolean = true,
    color: Color = Color.Transparent,
    padding: Dp = 10.dp
) = BaseLoadingImage(
    ImageRequest.Builder(LocalContext.current).data(uri).crossfade(crossfade).build(),
    modifier,
    color,
    padding
)

@Composable
fun LoadingImageApp(
    packageName: String,
    modifier: Modifier = Modifier.height(120.dp).fillMaxSize(),
    crossfade: Boolean = false,
    color: Color = Color.Transparent,
    padding: Dp = 10.dp
) = BaseLoadingImage(
    ImageRequest.Builder(LocalContext.current).data(LocalContext.current.getAppIcon(packageName)).crossfade(crossfade).build(),
    modifier,
    color,
    padding
)
