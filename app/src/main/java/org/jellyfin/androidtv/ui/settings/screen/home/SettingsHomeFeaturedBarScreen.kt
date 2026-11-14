package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.FeaturedBarTrailerArea
import org.jellyfin.androidtv.preference.constant.FeaturedBarWatchedFilter
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.form.RangeControl
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListControl
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.design.Tokens
import org.jellyfin.preference.Preference
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Libraries that can contain movies and series, null for libraries of mixed content.
 */
private val FeaturedLibraryTypes = setOf(
	null,
	CollectionType.UNKNOWN,
	CollectionType.MOVIES,
	CollectionType.TVSHOWS,
	CollectionType.BOXSETS,
	CollectionType.FOLDERS,
)

/**
 * Settings of the Featured Content Bar shown at the top of the home screen.
 */
@Composable
fun SettingsHomeFeaturedBarScreen() {
	val userPreferences = koinInject<UserPreferences>()
	val userViewsRepository = koinInject<UserViewsRepository>()
	val libraries by remember(userViewsRepository) {
		userViewsRepository.views.map { views -> views.filter { it.collectionType in FeaturedLibraryTypes } }
	}.collectAsState(emptyList())

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.featured_bar)) },
			)
		}

		item {
			var enabled by rememberPreference(userPreferences, UserPreferences.featuredBarEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.featured_bar_enabled)) },
				captionContent = { Text(stringResource(R.string.featured_bar_enabled_description)) },
				trailingContent = { Checkbox(checked = enabled) },
				onClick = { enabled = !enabled },
				modifier = Modifier.focusKey("featured_bar_enabled")
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.featured_bar_library)) }) }

		// All libraries first, then the libraries that can contain movies and series
		items(listOf(null) + libraries, key = { it?.id ?: "all" }) { library ->
			var libraryId by rememberPreference(userPreferences, UserPreferences.featuredBarLibrary)
			val id = library?.id?.toString().orEmpty()

			ListButton(
				headingContent = { Text(library?.name ?: stringResource(R.string.featured_bar_library_all)) },
				trailingContent = { RadioButton(checked = libraryId == id) },
				onClick = { libraryId = id },
				modifier = Modifier.focusKey("featured_bar_library_${id.ifEmpty { "all" }}")
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.featured_bar_watched)) }) }

		items(FeaturedBarWatchedFilter.entries) { filter ->
			var watchedFilter by rememberPreference(userPreferences, UserPreferences.featuredBarWatchedFilter)

			ListButton(
				headingContent = { Text(stringResource(filter.nameRes)) },
				trailingContent = { RadioButton(checked = watchedFilter == filter) },
				onClick = { watchedFilter = filter },
				modifier = Modifier.focusKey("featured_bar_watched_${filter.name}")
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.featured_bar_trailers)) }) }

		item {
			var autoplay by rememberPreference(userPreferences, UserPreferences.featuredBarTrailerAutoplay)

			ListButton(
				headingContent = { Text(stringResource(R.string.featured_bar_trailer_autoplay)) },
				captionContent = { Text(stringResource(R.string.featured_bar_trailer_autoplay_description)) },
				trailingContent = { Checkbox(checked = autoplay) },
				onClick = { autoplay = !autoplay },
				modifier = Modifier.focusKey("featured_bar_trailer_autoplay")
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.featured_bar_trailer_area)) }) }

		items(FeaturedBarTrailerArea.entries) { area ->
			var trailerArea by rememberPreference(userPreferences, UserPreferences.featuredBarTrailerArea)

			ListButton(
				headingContent = { Text(stringResource(area.nameRes)) },
				trailingContent = { RadioButton(checked = trailerArea == area) },
				onClick = { trailerArea = area },
				modifier = Modifier.focusKey("featured_bar_trailer_area_${area.name}")
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.featured_bar_volume)) }) }

		item {
			VolumeControl(
				userPreferences = userPreferences,
				preference = UserPreferences.featuredBarStartVolume,
				label = stringResource(R.string.featured_bar_start_volume),
				focusKey = "featured_bar_start_volume",
			)
		}

		item {
			VolumeControl(
				userPreferences = userPreferences,
				preference = UserPreferences.featuredBarFocusVolume,
				label = stringResource(R.string.featured_bar_focus_volume),
				focusKey = "featured_bar_focus_volume",
			)
		}
	}
}

/**
 * A volume from 0 (muted) to 100 %, in steps of 5 %.
 */
@Composable
private fun VolumeControl(
	userPreferences: UserPreferences,
	preference: Preference<Int>,
	label: String,
	focusKey: String,
) {
	var volume by rememberPreference(userPreferences, preference)
	val interactionSource = remember { MutableInteractionSource() }

	ListControl(
		headingContent = { Text(label) },
		interactionSource = interactionSource,
		modifier = Modifier.focusKey(focusKey)
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
		) {
			RangeControl(
				modifier = Modifier
					.height(4.dp)
					.weight(1f),
				interactionSource = interactionSource,
				min = 0f,
				max = 100f,
				stepForward = 5f,
				value = volume.toFloat(),
				onValueChange = { volume = it.roundToInt() }
			)

			Spacer(Modifier.width(Tokens.Space.spaceSm))

			Box(
				modifier = Modifier.sizeIn(minWidth = 40.dp),
				contentAlignment = Alignment.CenterEnd
			) {
				Text("$volume %")
			}
		}
	}
}
