package org.jellyfin.androidtv.ui.home.featured

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.getQuantityString
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

private const val CROSSFADE_DURATION_MS = 600
private const val MAX_GENRES = 3
private const val TICKS_PER_MINUTE = 600_000_000L

internal val FeaturedCornerRadius = 12.dp

/**
 * What the Featured Content Bar shows, changed by [FeaturedContentView].
 */
@Stable
internal class FeaturedContentState {
	var item by mutableStateOf<BaseItemDto?>(null, referentialEqualityPolicy())
	var focused by mutableStateOf(false)
	var canGoBack by mutableStateOf(false)

	/**
	 * A trailer is playing.
	 */
	var trailerShown by mutableStateOf(false)

	/**
	 * The trailer only fills the right half of the bar.
	 */
	var trailerOnRightHalf by mutableStateOf(false)
}

/**
 * The backdrop of the item, below the trailer.
 */
@Composable
internal fun FeaturedBackdrop(
	state: FeaturedContentState,
	api: ApiClient,
	imageLoader: ImageLoader,
	maxWidth: Int,
) {
	val item = state.item
	val url = remember(item?.id) { item?.itemBackdropImages?.firstOrNull()?.getUrl(api, maxWidth = maxWidth) }

	Crossfade(
		targetState = url,
		animationSpec = tween(CROSSFADE_DURATION_MS),
		label = "FeaturedBackdrop",
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black),
	) { backdropUrl ->
		if (backdropUrl != null) {
			AsyncImage(
				model = backdropUrl,
				contentDescription = null,
				imageLoader = imageLoader,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

/**
 * Everything above the trailer: shades for the texts, logo or title, details, overview and the focus.
 */
@Composable
internal fun FeaturedOverlay(
	state: FeaturedContentState,
	api: ApiClient,
	imageLoader: ImageLoader,
) {
	Box(Modifier.fillMaxSize()) {
		// Keep the texts readable over the backdrop and the trailer
		Box(
			Modifier
				.fillMaxSize()
				.background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.6f to Color.Transparent))
		)
		Box(
			Modifier
				.fillMaxSize()
				.background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)))
		)

		// Hide the edge of a trailer on the right half, it fades in and out with the trailer
		val seamAlpha by animateFloatAsState(
			targetValue = if (state.trailerShown && state.trailerOnRightHalf) 1f else 0f,
			animationSpec = tween(CROSSFADE_DURATION_MS),
			label = "FeaturedSeam",
		)
		if (seamAlpha > 0f) {
			Box(
				Modifier
					.fillMaxSize()
					.graphicsLayer { alpha = seamAlpha }
					.background(
						Brush.horizontalGradient(
							0.46f to Color.Transparent,
							0.5f to Color.Black.copy(alpha = 0.9f),
							0.6f to Color.Transparent,
						)
					)
			)
		}

		Crossfade(
			targetState = state.item,
			animationSpec = tween(CROSSFADE_DURATION_MS),
			label = "FeaturedDetails",
			modifier = Modifier
				.align(Alignment.CenterStart)
				.fillMaxWidth(0.5f)
				.padding(start = 40.dp, end = 16.dp),
		) { item ->
			if (item != null) FeaturedDetails(item, api, imageLoader)
		}

		if (state.focused) {
			Chevron(
				pointsLeft = true,
				visible = state.canGoBack,
				modifier = Modifier
					.align(Alignment.CenterStart)
					.padding(start = 12.dp)
			)
			Chevron(
				pointsLeft = false,
				visible = true,
				modifier = Modifier
					.align(Alignment.CenterEnd)
					.padding(end = 12.dp)
			)

			Box(
				Modifier
					.fillMaxSize()
					.border(3.dp, Color.White, RoundedCornerShape(FeaturedCornerRadius))
			)
		}
	}
}

@Composable
private fun FeaturedDetails(
	item: BaseItemDto,
	api: ApiClient,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	val logoUrl = remember(item.id) { item.itemImages[ImageType.LOGO]?.getUrl(api, maxWidth = 600) }
	val details = remember(item.id) {
		buildList {
			item.productionYear?.let { add(it.toString()) }
			when (item.type) {
				BaseItemKind.SERIES -> item.childCount?.takeIf { it > 0 }?.let {
					add(context.getQuantityString(R.plurals.featured_bar_seasons, it))
				}

				else -> item.runTimeTicks?.let { it / TICKS_PER_MINUTE }?.takeIf { it > 0 }?.let {
					add(context.getString(R.string.featured_bar_minutes, it.toInt()))
				}
			}
			item.officialRating?.let { add(it) }
		}.joinToString("   ")
	}
	val genres = remember(item.id) { item.genres.orEmpty().take(MAX_GENRES).joinToString("  •  ") }

	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		if (logoUrl != null) {
			AsyncImage(
				model = logoUrl,
				contentDescription = item.name,
				imageLoader = imageLoader,
				contentScale = ContentScale.Fit,
				alignment = Alignment.CenterStart,
				modifier = Modifier
					.heightIn(max = 90.dp)
					.widthIn(max = 360.dp),
			)
		} else {
			Text(
				text = item.name.orEmpty(),
				color = Color.White,
				fontSize = 30.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (details.isNotEmpty()) Text(text = details, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)

			item.communityRating?.let { rating ->
				Row(verticalAlignment = Alignment.CenterVertically) {
					Icon(
						painter = painterResource(R.drawable.ic_star),
						contentDescription = null,
						tint = Color(0xFFFFC107),
						modifier = Modifier.size(14.dp),
					)
					Text(text = " %.1f".format(rating), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
				}
			}
		}

		if (genres.isNotEmpty()) Text(text = genres, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1)

		item.overview?.let { overview ->
			Text(
				text = overview,
				color = Color.White.copy(alpha = 0.85f),
				fontSize = 14.sp,
				lineHeight = 20.sp,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Left and right go to the previous and next item while the bar has the focus.
 */
@Composable
private fun Chevron(
	pointsLeft: Boolean,
	visible: Boolean,
	modifier: Modifier = Modifier,
) {
	if (!visible) return

	Canvas(modifier.size(width = 14.dp, height = 28.dp)) {
		val start = if (pointsLeft) size.width else 0f
		val tip = if (pointsLeft) 0f else size.width
		val stroke = 3.dp.toPx()
		val color = Color.White.copy(alpha = 0.9f)

		drawLine(color, Offset(start, 0f), Offset(tip, size.height / 2f), stroke, StrokeCap.Round)
		drawLine(color, Offset(tip, size.height / 2f), Offset(start, size.height), stroke, StrokeCap.Round)
	}
}
