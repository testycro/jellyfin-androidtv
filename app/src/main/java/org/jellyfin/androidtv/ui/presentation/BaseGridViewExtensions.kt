package org.jellyfin.androidtv.ui.presentation

import androidx.leanback.widget.BaseGridView

/**
 * Number of rows kept bound outside of the screen (the default is 2), so going back up or down shows them right away
 * instead of binding all their cards again.
 */
private const val ROWS_VIEW_CACHE_SIZE = 4

/**
 * Keep more rows (or cards of a grid) bound when they leave the screen, see [ROWS_VIEW_CACHE_SIZE].
 */
fun BaseGridView.increaseRowsViewCache() {
	setItemViewCacheSize(ROWS_VIEW_CACHE_SIZE)
}

/**
 * Let the list prepare the next row (and its first cards, see [CustomListRowPresenter]) in the spare time between
 * frames while scrolling, instead of creating it in the frame where it scrolls into view. Leanback turns this off by
 * default.
 */
fun BaseGridView.enableItemPrefetch() {
	layoutManager?.isItemPrefetchEnabled = true
}
