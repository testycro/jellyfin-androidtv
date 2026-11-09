package org.jellyfin.androidtv.util.sdk

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Trailers the user removed from the list of an item, stored on this device.
 *
 * The Jellyfin server has no API to remove a single remote trailer (updating an item ignores its trailers) and a metadata
 * refresh would add the trailer again, so removed trailers are remembered here by [TrailerUtils.getTrailerKey].
 */
class HiddenTrailers(context: Context) {
	private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun get(itemId: UUID): Set<String> = preferences.getStringSet(itemId.toString(), null).orEmpty().toSet()

	fun hide(itemId: UUID, key: String) {
		preferences.edit { putStringSet(itemId.toString(), get(itemId) + key) }
	}

	/**
	 * Show the trailers with these keys again.
	 */
	fun show(itemId: UUID, keys: Set<String>) {
		if (keys.isEmpty()) return

		val remaining = get(itemId) - keys
		preferences.edit {
			if (remaining.isEmpty()) remove(itemId.toString())
			else putStringSet(itemId.toString(), remaining)
		}
	}

	private companion object {
		const val PREFERENCES_NAME = "hidden_trailers"
	}
}
