package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.View
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardImagePreloader
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.ui.presentation.enablePrefetch
import org.jellyfin.androidtv.ui.presentation.keepFocusWhenScrollingUp
import org.jellyfin.androidtv.ui.presentation.keepsFocus
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject

abstract class BrowseFolderFragment : BrowseSupportFragment(), RowLoader {
	protected var folder: BaseItemDto? = null
	protected var includeType: String? = null

	protected var rows = mutableListOf<BrowseRowDef>()

	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()

	// Shared by all rows, so their cards can be recycled from one row to another
	private val cardPresenter = CardPresenter()
	private val imagePreloader by lazy { CardImagePreloader(requireContext(), this) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Parse intent
		folder = Json.decodeFromString<BaseItemDto>(arguments?.getString(Extras.Folder)!!)
		includeType = arguments?.getString(Extras.IncludeType)

		// Set BrowseSupportFragment properties
		title = folder?.name
		headersState = HEADERS_DISABLED

		// Add event listeners
		onItemViewClickedListener = OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, row: Row ->
			if (item is BaseRowItem) {
				itemLauncher.launch(
					item,
					(row as ListRow).adapter as ItemRowAdapter,
					requireContext()
				)
			}
		}
		onItemViewSelectedListener = OnItemViewSelectedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, row: Row ->
			if (item !is BaseRowItem) {
				backgroundService.clearBackgrounds()
			} else {
				val adapter = (row as? ListRow)?.adapter
				if (adapter is ItemRowAdapter) adapter.loadMoreItemsIfNeeded(adapter.indexOf(item))

				backgroundService.setBackground(item.baseItem)
				imagePreloader.preloadAroundRow(this@BrowseFolderFragment.adapter, row, item)
			}
		}

		// Initialize
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
				setupQueries(this@BrowseFolderFragment)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// Going up from the rows moves the focus to the title, which is only meant to happen from the first row. Going
		// up quickly, the rows let the focus go long before the first row is reached.
		val browseFrame = view.findViewById<BrowseFrameLayout>(androidx.leanback.R.id.browse_frame) ?: return
		val titleFocusSearch = browseFrame.onFocusSearchListener

		browseFrame.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
			val rows = rowsSupportFragment?.verticalGridView

			if (rows != null && rows.keepsFocus(focused, direction)) focused
			else titleFocusSearch?.onFocusSearch(focused, direction)
		}
	}

	override fun onResume() {
		super.onResume()

		rowsSupportFragment?.verticalGridView?.enablePrefetch()
		rowsSupportFragment?.verticalGridView?.keepFocusWhenScrollingUp()
	}

	override fun onPause() {
		imagePreloader.cancel()
		super.onPause()
	}

	protected abstract suspend fun setupQueries(rowLoader: RowLoader)

	override fun loadRows(rows: MutableList<BrowseRowDef>) {
		val mutableAdapter = MutableObjectAdapter<Row>(PositionableListRowPresenter()).also {
			adapter = it
		}

		for (def in rows) {
			ItemRowAdapter(
				requireContext(),
				def.query,
				def.chunkSize,
				def.preferParentThumb,
				def.isStaticHeight,
				cardPresenter,
				mutableAdapter,
				def.queryType
			).apply {
				val row = ListRow(HeaderItem(def.headerText), this)
				setReRetrieveTriggers(def.changeTriggers)
				setRow(row)
				Retrieve()
				mutableAdapter.add(row)
			}
		}
	}
}
