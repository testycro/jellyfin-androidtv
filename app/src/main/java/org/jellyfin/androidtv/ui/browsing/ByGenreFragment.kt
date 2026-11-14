package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.shared.TitleView
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject

class ByGenreFragment : BrowseFolderFragment() {
	private val apiClient by inject<ApiClient>()
	private val preferencesRepository by inject<PreferencesRepository>()

	private val libraryPreferences by lazy {
		folder?.displayPreferencesId?.let(preferencesRepository::getLibraryPreferences)
	}

	private var unwatchedButton: ImageButton? = null
	private var unwatchedOnly = false
	private var unwatchedOnlyLoaded = false
	private var reloadJob: Job? = null

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val titleView = view.findViewById<View>(R.id.browse_title_group) as? TitleView ?: return
		val buttonSize = (26 * resources.displayMetrics.density).toInt()

		unwatchedButton = ImageButton(requireContext(), null, 0, R.style.Button_Icon).apply {
			setImageResource(R.drawable.ic_unwatch)
			maxHeight = buttonSize
			adjustViewBounds = true
			isActivated = unwatchedOnly
			contentDescription = getString(R.string.lbl_unwatched)
			setOnClickListener { toggleUnwatchedOnly() }
		}

		titleView.actionsContainer.apply {
			removeAllViews()
			addView(unwatchedButton)
			isVisible = true
		}
	}

	override fun onDestroyView() {
		unwatchedButton = null
		super.onDestroyView()
	}

	private fun toggleUnwatchedOnly() {
		unwatchedOnly = !unwatchedOnly
		unwatchedButton?.isActivated = unwatchedOnly

		// Remember the choice for this library
		val value = unwatchedOnly
		lifecycleScope.launch(Dispatchers.IO) {
			libraryPreferences?.let { preferences ->
				preferences[LibraryPreferences.filterUnwatchedOnlyByGenre] = value
				preferences.commit()
			}
		}

		// Rebuild all genre rows with the new filter
		reloadJob?.cancel()
		reloadJob = lifecycleScope.launch { setupQueries(this@ByGenreFragment) }
	}

	override suspend fun setupQueries(rowLoader: RowLoader) {
		val childCount = folder?.childCount ?: 0
		if (childCount <= 0) return

		// Restore the saved filter state the first time
		if (!unwatchedOnlyLoaded) {
			unwatchedOnly = withContext(Dispatchers.IO) {
				libraryPreferences?.get(LibraryPreferences.filterUnwatchedOnlyByGenre) ?: false
			}
			unwatchedOnlyLoaded = true
			unwatchedButton?.isActivated = unwatchedOnly
		}

		// Get all genres for this folder
		val genresResponse = withContext(Dispatchers.IO) {
			apiClient.genresApi.getGenres(
				parentId = folder?.id,
				sortBy = setOf(ItemSortBy.SORT_NAME),
			).content
		}

		// Rows for genres without (unwatched) items are removed automatically once loaded
		val genreRows = mutableListOf<BrowseRowDef>()
		for (genre in genresResponse.items) {
			val itemsRequest = GetItemsRequest(
				parentId = folder?.id,
				sortBy = setOf(ItemSortBy.SORT_NAME),
				includeItemTypes = includeType?.let(BaseItemKind::fromNameOrNull)?.let(::setOf),
				genres = setOf(genre.name.orEmpty()),
				filters = if (unwatchedOnly) setOf(ItemFilter.IS_UNPLAYED) else null,
				recursive = true,
				fields = ItemRepository.itemFields,
			)
			genreRows.add(BrowseRowDef(genre.name, itemsRequest, 40))
		}

		rowLoader.loadRows(genreRows)
	}
}
