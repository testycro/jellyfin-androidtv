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
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject

class ByLetterFragment : BrowseFolderFragment() {
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
				preferences[LibraryPreferences.filterUnwatchedOnlyByLetter] = value
				preferences.commit()
			}
		}

		// Rebuild all letter rows with the new filter
		reloadJob?.cancel()
		reloadJob = lifecycleScope.launch { setupQueries(this@ByLetterFragment) }
	}

	override suspend fun setupQueries(rowLoader: RowLoader) {
		val childCount = folder?.childCount ?: 0
		if (childCount <= 0) return

		// Restore the saved filter state the first time
		if (!unwatchedOnlyLoaded) {
			unwatchedOnly = withContext(Dispatchers.IO) {
				libraryPreferences?.get(LibraryPreferences.filterUnwatchedOnlyByLetter) ?: false
			}
			unwatchedOnlyLoaded = true
			unwatchedButton?.isActivated = unwatchedOnly
		}

		val letters = getString(R.string.byletter_letters)
		val itemFilters = if (unwatchedOnly) setOf(ItemFilter.IS_UNPLAYED) else null

		// Local list: the filter can be toggled, so the rows must be rebuilt from scratch each time
		val letterRows = mutableListOf<BrowseRowDef>()

		// Add a '#' item
		val numbersItemsRequest = GetItemsRequest(
			parentId = folder?.id,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			includeItemTypes = includeType?.let(BaseItemKind::fromNameOrNull)?.let(::setOf),
			nameLessThan = letters.substring(0, 1),
			filters = itemFilters,
			recursive = true,
			fields = ItemRepository.browseFields,
		)

		letterRows.add(BrowseRowDef("#", numbersItemsRequest, 40))

		// Add all the defined letters
		// Rows for letters without (unwatched) items are removed automatically once loaded
		for (letter in letters.toCharArray()) {
			val letterItemsRequest = GetItemsRequest(
				parentId = folder?.id,
				sortBy = setOf(ItemSortBy.SORT_NAME),
				includeItemTypes = includeType?.let(BaseItemKind::fromNameOrNull)?.let(::setOf),
				nameStartsWith = letter.toString(),
				filters = itemFilters,
				recursive = true,
				fields = ItemRepository.browseFields,
			)

			letterRows.add(BrowseRowDef(letter.toString(), letterItemsRequest, 40))
		}

		rowLoader.loadRows(letterRows)
	}
}
