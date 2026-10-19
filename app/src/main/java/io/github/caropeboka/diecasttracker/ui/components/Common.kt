package io.github.caropeboka.diecasttracker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** The app-wide snackbar host; every screen's Scaffold shows it. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Saira Condensed, uppercase — the web app's `font-heading`. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Text(text.uppercase(), modifier = modifier, style = style, maxLines = 1)
}

/** A card-coloured panel with a heading, like the web form's sections. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Heading(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.titleLarge.fontFamily))
            }
            content()
        }
    }
}

/** Icon and label inside a button. */
@Composable
fun IconLabel(@DrawableRes icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, maxLines = 1)
}

/** A stored photo by file name, or a car silhouette when there is none. */
@Composable
fun DiecastPhoto(
    fileName: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderSize: Dp = 48.dp,
) {
    val photos = LocalContext.current.container.photos
    Box(modifier.background(Garage.Muted), contentAlignment = Alignment.Center) {
        if (fileName == null) {
            Icon(
                painterResource(R.drawable.ic_car),
                contentDescription = null,
                tint = Garage.MutedForeground.copy(alpha = 0.4f),
                modifier = Modifier.size(placeholderSize),
            )
        } else {
            AsyncImage(
                model = photos.file(fileName),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
