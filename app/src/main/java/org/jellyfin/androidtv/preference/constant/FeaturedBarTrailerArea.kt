package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/**
 * Where the trailers of the Featured Content Bar of the home screen are shown.
 */
enum class FeaturedBarTrailerArea(
	override val nameRes: Int,
) : PreferenceEnum {
	FULL(R.string.featured_bar_trailer_area_full),
	RIGHT_HALF(R.string.featured_bar_trailer_area_right_half),
}
