package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.design.Tokens
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.compose.koinInject

@Composable
@Stable
fun ItemCardBaseItemOverlay(
	item: BaseItemDto,
	footer: (@Composable () -> Unit)? = null,
) = Box(
	modifier = Modifier
		.fillMaxSize()
		.padding(Tokens.Space.spaceXs)
) {
	StateIndicator(
		item = item,
		modifier = Modifier.align(Alignment.TopStart),
	)

	WatchIndicator(
		item = item,
		modifier = Modifier.align(Alignment.TopEnd)
	)

	BottomContent(
		item = item,
		footer = footer,
		modifier = Modifier.align(Alignment.BottomCenter),
	)
}

@Composable
private fun BottomContent(
	item: BaseItemDto,
	footer: (@Composable () -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val playedPercentage = rememberPlayedPercentage(item)

	// Most cards have no progress and no footer, don't add an empty layout to them
	if (playedPercentage == null && footer == null) return

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs)
	) {
		if (playedPercentage != null) ProgressIndicator(progress = playedPercentage)
		if (footer != null) footer()
	}
}

@Composable
@Stable
private fun StateIndicator(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
) {
	val isFavorited = item.userData?.isFavorite == true
	val isRecording = item.timerId?.takeIf { item.type == BaseItemKind.LIVE_TV_PROGRAM || item.type == BaseItemKind.PROGRAM } != null
	val isRecordingActive = item.seriesTimerId != null && isRecording

	// Most cards have no state to show, don't add an empty layout to them
	if (!isRecording && !isFavorited) return

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs)
	) {
		if (isRecording) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_record_series),
				contentDescription = null,
				tint = if (isRecordingActive) Tokens.Color.colorRed600 else Tokens.Color.colorGrey100,
				modifier = modifier
					.size(24.dp)
			)
		}

		if (isFavorited) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_heart),
				contentDescription = null,
				tint = Tokens.Color.colorRed500,
				modifier = modifier
					.size(24.dp)
			)
		}
	}
}

@Composable
@Stable
private fun WatchIndicator(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
) {
	val userPreferences = koinInject<UserPreferences>()
	val watchedIndicatorBehavior = userPreferences[UserPreferences.watchedIndicatorBehavior]

	if (watchedIndicatorBehavior == WatchedIndicatorBehavior.NEVER) return
	if (watchedIndicatorBehavior == WatchedIndicatorBehavior.EPISODES_ONLY && item.type != BaseItemKind.EPISODE) return

	val isPlayed = item.userData?.played == true
	val unplayedItems = item.userData?.unplayedItemCount?.takeIf { it > 0 }

	if (isPlayed) {
		Badge(
			modifier = modifier
				.size(24.dp),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_watch),
				contentDescription = null,
				modifier = Modifier.size(12.dp)
			)
		}
	} else if (unplayedItems != null) {
		if (watchedIndicatorBehavior == WatchedIndicatorBehavior.HIDE_UNWATCHED) return

		Badge(
			modifier = modifier
				.sizeIn(minWidth = 24.dp, minHeight = 24.dp),
		) {
			Text(
				text = unplayedItems.toString(),
			)
		}
	}
}

// Media types that need to show a user progress bar (based on playedPercentage in userData)
private val progressMediaTypes = setOf(MediaType.VIDEO, MediaType.BOOK)

/**
 * Progress of [item] while it is the item that is playing, null when it isn't.
 */
@Composable
private fun rememberPlayingProgress(item: BaseItemDto): Float? {
	val playbackManager = koinInject<PlaybackManager>()
	val playState by playbackManager.state.playState.collectAsState()
	val currentQueueEntry by rememberQueueEntry(playbackManager)

	return when {
		playState == PlayState.PLAYING && currentQueueEntry?.baseItem?.id == item.id -> rememberPlayerProgress(playbackManager).value
		else -> null
	}
}

/**
 * The progress to show for [item], null when there is none.
 */
@Composable
private fun rememberPlayedPercentage(item: BaseItemDto): Float? {
	val savedProgress = when (item.mediaType) {
		in progressMediaTypes -> item.userData?.playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)
		else -> null
	}

	// Following the playback state costs two flow collectors for every card on screen. Only audio items (tracks in a
	// list) show the live progress while they play, the other items show their saved progress
	return when (item.mediaType) {
		MediaType.AUDIO -> rememberPlayingProgress(item) ?: savedProgress
		else -> savedProgress
	}
}

/**
 * The progress bar of a card. It looks like the Seekbar but is only drawn: a Seekbar also handles focus, keys and an
 * animated knob, which is a lot to create for every card that is partly watched.
 */
@Composable
private fun ProgressIndicator(
	progress: Float,
	modifier: Modifier = Modifier,
) {
	val backgroundColor = JellyfinTheme.colorScheme.rangeControlBackground
	val progressColor = JellyfinTheme.colorScheme.rangeControlFill

	Box(
		modifier = modifier
			.padding(Tokens.Space.spaceXs)
			.fillMaxWidth()
			.height(4.dp)
			.drawBehind {
				val cornerRadius = CornerRadius(size.minDimension, size.minDimension)

				drawRoundRect(
					color = backgroundColor,
					cornerRadius = cornerRadius,
				)

				if (progress > 0f) {
					drawRoundRect(
						color = progressColor,
						size = size.copy(width = progress * size.width),
						cornerRadius = cornerRadius,
					)
				}
			}
	)
}
