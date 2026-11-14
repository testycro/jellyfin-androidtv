package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.design.Tokens

@Composable
@Stable
fun ItemPreview(
	card: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	title: (@Composable () -> Unit)? = null,
	subtitle: (@Composable () -> Unit)? = null,
	spacing: Dp = Tokens.Space.spaceXs,
	cardWidth: Dp? = null,
) {
	// When the width of the card is known the layout is a plain column. The measuring layout below composes the card
	// and the metadata separately, which doubles the composition work of every item
	if (cardWidth != null) {
		Column(
			modifier = modifier.width(cardWidth),
			verticalArrangement = Arrangement.spacedBy(spacing),
		) {
			card()

			ItemPreviewMetadata(
				title = title,
				subtitle = subtitle,
				spacing = spacing,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		return
	}

	ItemPreviewLayout(
		card = card,
		metadata = {
			ItemPreviewMetadata(
				title = title,
				subtitle = subtitle,
				spacing = spacing,
			)
		},
		spacing = spacing,
		modifier = modifier
	)
}

@Composable
@Stable
private fun ItemPreviewMetadata(
	title: (@Composable () -> Unit)?,
	subtitle: (@Composable () -> Unit)?,
	spacing: Dp,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.padding(spacing),
		verticalArrangement = Arrangement.spacedBy(spacing),
	) {
		title?.let { content ->
			ProvideTextStyle(
				value = JellyfinTheme.typography.default.copy(
					color = Tokens.Color.colorGrey100,
					fontSize = 12.sp,
				),
				content = content,
			)
		}

		subtitle?.let { content ->
			ProvideTextStyle(
				value = JellyfinTheme.typography.default.copy(
					color = Tokens.Color.colorGrey300,
					fontSize = 10.sp,
				),
				content = content,
			)
		}
	}
}

@Composable
@Stable
fun ItemPreviewLayout(
	card: @Composable () -> Unit,
	metadata: @Composable () -> Unit,
	spacing: Dp = Tokens.Space.spaceXs,
	modifier: Modifier,
) {
	SubcomposeLayout(
		modifier = modifier,
	) { constraints ->
		val cardPlaceables = subcompose("card", card).map { it.measure(constraints) }
		val cardWidth = cardPlaceables.maxOf { it.width }
		val cardHeight = cardPlaceables.maxOf { it.height }

		val childConstraints = constraints.copy(minWidth = cardWidth, maxWidth = cardWidth)
		val metadataPlaceables = subcompose("metadata", metadata).map { it.measure(childConstraints) }
		val metadataHeight = metadataPlaceables.maxOf { it.height }

		val spacingPx = spacing.roundToPx()
		val height = cardHeight + spacingPx + metadataHeight

		layout(cardWidth, height) {
			cardPlaceables.forEach { it.place(0, 0) }
			metadataPlaceables.forEach { it.place(0, cardHeight + spacingPx) }
		}
	}
}
