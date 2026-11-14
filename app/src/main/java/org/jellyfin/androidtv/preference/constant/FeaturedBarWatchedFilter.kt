package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/**
 * Which items the Featured Content Bar of the home screen shows.
 */
enum class FeaturedBarWatchedFilter(
	override val nameRes: Int,
	/**
	 * The value for the IsPlayed filter of the items query, null for all items.
	 */
	val isPlayed: Boolean?,
) : PreferenceEnum {
	ALL(R.string.featured_bar_watched_all, null),
	UNWATCHED(R.string.featured_bar_watched_unwatched, false),
	WATCHED(R.string.featured_bar_watched_watched, true),
}
