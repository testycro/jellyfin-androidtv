package org.jellyfin.androidtv.ui.presentation

import android.view.View
import androidx.core.view.isVisible
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter

/**
 * Number of cards of a row kept bound outside of the screen, in each direction of scrolling.
 */
private const val ROW_ITEM_VIEW_CACHE_SIZE = 10

/**
 * Number of cards of a row prepared ahead of time, about the number of cards visible on screen.
 */
private const val ROW_INITIAL_PREFETCH_COUNT = 8

open class CustomListRowPresenter @JvmOverloads constructor(
	private val topPadding: Int? = null
) : ListRowPresenter() {
	init {
		headerPresenter = CustomRowHeaderPresenter()
	}

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
		super.initializeRowViewHolder(holder)

		val gridView = (holder as? ViewHolder)?.gridView ?: return

		// Keep more cards bound when they leave the screen (the default is 2): scrolling back shows them right away,
		// without binding them and loading their image again
		gridView.setItemViewCacheSize(ROW_ITEM_VIEW_CACHE_SIZE)

		// Prepare the next card in the spare time between frames when scrolling sideways, and the first cards of the
		// row when the list of rows prepares this row ahead of time (see enableItemPrefetch). Leanback turns it off.
		gridView.layoutManager?.isItemPrefetchEnabled = true
		gridView.initialPrefetchItemCount = ROW_INITIAL_PREFETCH_COUNT
	}

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)

		val view = holder.view?.parent as? View ?: return
		if (topPadding != null) view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)

		// Hide header view when the item doesn't have one
		holder.headerViewHolder.view.isVisible = !(item is ListRow && item.headerItem == null)
	}
}
