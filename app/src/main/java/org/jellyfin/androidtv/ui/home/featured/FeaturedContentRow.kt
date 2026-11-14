package org.jellyfin.androidtv.ui.home.featured

import android.view.ViewGroup
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

/**
 * The Featured Content Bar at the top of the home screen, like the bar of the finality theme of jellyfin-web: movies
 * and series one after the other, with their backdrop, logo, details and trailer.
 *
 * @param items The first items to show.
 */
class FeaturedContentRow(val items: List<BaseItemDto>) : Row()

class FeaturedContentPresenter : RowPresenter() {
	init {
		// The bar is the whole row: no header, no dimming of the row
		headerPresenter = null
		selectEffectEnabled = false
	}

	override fun isUsingDefaultSelectEffect() = false

	override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder =
		RowPresenter.ViewHolder(FeaturedContentView(parent.context))

	override fun onBindRowViewHolder(vh: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(vh, item)

		val row = item as? FeaturedContentRow ?: return
		(vh.view as FeaturedContentView).setInitialItems(row.items)
	}

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit
}

/**
 * Items shown by the Featured Content Bar.
 */
object FeaturedContentLoader {
	const val BATCH_SIZE = 20

	/**
	 * Random movies and series with a backdrop, from the library and with the watched state chosen in the settings.
	 * Empty when there are none or the server can't be reached.
	 */
	suspend fun load(api: ApiClient, userPreferences: UserPreferences): List<BaseItemDto> = withContext(Dispatchers.IO) {
		val filter = userPreferences[UserPreferences.featuredBarWatchedFilter]
		val libraryId = userPreferences[UserPreferences.featuredBarLibrary].toUUIDOrNull()

		try {
			val response by api.itemsApi.getItems(
				parentId = libraryId,
				includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				recursive = true,
				sortBy = listOf(ItemSortBy.RANDOM),
				limit = BATCH_SIZE,
				isPlayed = filter.isPlayed,
				imageTypes = listOf(ImageType.BACKDROP),
				fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.CHILD_COUNT),
			)
			response.items
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to load the items of the Featured Content Bar")
			emptyList()
		}
	}
}

/**
 * The index for rows that are added at the top of the home screen later on (notifications, now playing): below the
 * Featured Content Bar, which stays at the top.
 */
fun MutableObjectAdapter<Row>.topRowIndex(): Int = if (size() > 0 && get(0) is FeaturedContentRow) 1 else 0
