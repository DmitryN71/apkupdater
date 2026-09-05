package com.apkupdater.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apkupdater.R
import com.apkupdater.data.ui.Source
import com.apkupdater.util.clickableNoRipple


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludeIcon(
    exclude: Boolean,
    @StringRes excludeString: Int,
    @StringRes includeString: Int,
    @DrawableRes excludeIcon: Int,
    @DrawableRes includeIcon: Int,
    @DrawableRes icon: Int = if (exclude) excludeIcon else includeIcon,
    @StringRes string: Int = if (exclude) includeString else excludeString,
    @StringRes contentDescription: Int = if (exclude) excludeString else includeString,
) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    state = rememberTooltipState(),
    tooltip = { PlainTooltip { Text(stringResource(string)) } }
) {
    Icon(painterResource(icon), stringResource(contentDescription))
}

@Composable
fun ExcludeSystemIcon(exclude: Boolean) = ExcludeIcon(
    exclude = exclude,
    excludeString = R.string.exclude_system_apps,
    includeString = R.string.include_system_apps,
    excludeIcon = R.drawable.ic_system_off,
    includeIcon = R.drawable.ic_system
)

@Composable
fun ExcludeAppStoreIcon(exclude: Boolean) = ExcludeIcon(
    exclude = exclude,
    excludeString = R.string.exclude_app_store,
    includeString = R.string.include_app_store,
    excludeIcon = R.drawable.ic_appstore_off,
    includeIcon = R.drawable.ic_appstore
)

@Composable
fun ExcludeDisabledIcon(exclude: Boolean) = ExcludeIcon(
    exclude = exclude,
    excludeString = R.string.exclude_disabled_apps,
    includeString = R.string.include_disabled_apps,
    excludeIcon = R.drawable.ic_disabled_off,
    includeIcon = R.drawable.ic_disabled
)

@Composable
fun SourceIcon(source: Source, modifier: Modifier = Modifier) = Icon(
    painterResource(id = source.resourceId),
    source.name,
    modifier
)

@Composable
fun IgnoreIcon(ignored: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) = Icon(
    painter = painterResource(
        id = if(ignored) R.drawable.ic_visible_off else R.drawable.ic_visible
    ),
    contentDescription = stringResource(if (ignored) R.string.unignore_cd else R.string.ignore_cd),
    modifier = Modifier.clickableNoRipple(onClick).then(modifier)
)

@Composable
fun InstallIcon(onClick: () -> Unit, modifier: Modifier = Modifier) = Icon(
    painter = painterResource(R.drawable.ic_install),
    contentDescription = stringResource(R.string.install_cd),
    modifier = Modifier.clickableNoRipple(onClick).then(modifier)
)

@Composable
fun BoxScope.InstallProgressIcon(
    isInstalling: Boolean,
    onClick: () -> Unit
) {
    if(isInstalling) {
        CircularProgressIndicator(
            Modifier.align(Alignment.TopEnd).size(30.dp).padding(4.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
    else {
        InstallIcon(
            { onClick() },
            Modifier.align(Alignment.TopEnd).padding(4.dp)
        )
    }
}

/**
 * What the Refresh button becomes while a check is running: a stop square inside a ring that
 * fills as the sources answer.
 *
 * A spinning refresh arrow was the first attempt and it read as "busy, wait" — nobody would
 * guess it could be pressed. The glyph says what the tap does; the ring says how far the
 * check has got.
 *
 * @param progress 0f..1f once at least one source has answered, null while the fraction is
 *   not yet meaningful — the ring then turns on its own instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopCheckingIcon(
    text: String,
    progress: Float?
) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    state = rememberTooltipState(),
    tooltip = { PlainTooltip { Text(text) } }
) {
    Box(contentAlignment = Alignment.Center) {
        if (progress == null) {
            // Nothing to report yet: the app list is still being read and the number of
            // sources is not even known. Spinning rather than showing an empty ring, because
            // the first thing a tap must do is move — a button that goes still on being
            // pressed is what build 140 set out to fix in the first place.
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )
        } else {
            // Sources answer in steps of one, so the ring is swept to each new value instead
            // of jumping to it. gapSize is zeroed to keep a plain ring: the default leaves a
            // notch at the head of the arc, which at 24 dp reads as a rendering fault.
            val swept = animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(durationMillis = 400)
            ).value
            CircularProgressIndicator(
                progress = { swept },
                modifier = Modifier.size(24.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
                trackColor = LocalContentColor.current.copy(alpha = 0.25f),
                gapSize = 0.dp
            )
        }
        // A square, not a cross. The cross means "close this" everywhere else in Android, and
        // what this button does is halt a running job — which is the square every media player
        // has used for forty years. Drawn rather than taken from the icon set so its size is
        // exactly what it looks like: Material's own stop glyph fills half its 24 dp box, so
        // asking for 9 dp there would have produced a 4.5 dp square.
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LocalContentColor.current)
                .semantics { contentDescription = text }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshIcon(
    text: String,
    modifier: Modifier = Modifier
) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    state = rememberTooltipState(),
    tooltip = { PlainTooltip { Text(text) } }
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_refresh),
        contentDescription = text,
        modifier = modifier
    )
}
