package org.jellyfin.androidtv.ui.itemdetail

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.util.dp
import org.jellyfin.androidtv.util.sdk.HiddenTrailers
import org.jellyfin.androidtv.util.sdk.TrailerUtils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemRefreshApi
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaUrl
import org.jellyfin.sdk.model.api.MetadataRefreshMode
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale
import java.util.UUID

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * Languages used to search trailers, in order. The next language is only tried when the previous one found nothing new.
 */
private val TRAILER_SEARCH_LANGUAGES = listOf("fr", "en")

/**
 * A metadata refresh runs in the background on the server and has no completion signal, so the item is polled until its
 * trailers change or the maximum waiting time ([REFRESH_POLL_INTERVAL_MS] * [REFRESH_POLL_ATTEMPTS]) is reached.
 */
private const val REFRESH_POLL_INTERVAL_MS = 2_000L
private const val REFRESH_POLL_ATTEMPTS = 8

private data class TrailerSearchResult(
	/** All remote trailers of the item after the search, as returned by the server. */
	val trailers: List<MediaUrl>,
	/** Number of trailers that were not on the server before the search. */
	val added: Int,
	/** Language the new trailers were found in, null when nothing new was found. */
	val language: String?,
	/** Keys of the trailers removed by the user that are in the list after the search, they are shown again. */
	val restoredKeys: Set<String>,
)

/**
 * Show all trailers of the current item in a dialog. Every trailer can be played or removed from the list and, for
 * administrators, a button at the bottom searches new trailers (French first, then English) without adding duplicates.
 */
fun FullDetailsFragment.showTrailers() {
	val api by inject<ApiClient>()
	val userRepository by inject<UserRepository>()
	val hiddenTrailers = HiddenTrailers(requireContext())
	val canSearch = TrailerUtils.canSearchTrailers(userRepository.currentUser.value)
	val hasLocalTrailers = (mBaseItem.localTrailerCount ?: 0) > 0
	val itemId = mBaseItem.id

	lifecycleScope.launch {
		val localTrailers = if (!hasLocalTrailers) emptyList() else try {
			withContext(Dispatchers.IO) {
				api.userLibraryApi.getLocalTrailers(itemId).content
			}
		} catch (exception: ApiClientException) {
			Timber.e(exception, "Error retrieving trailers for playback")
			Toast.makeText(requireContext(), getString(R.string.msg_video_playback_error), Toast.LENGTH_LONG).show()
			emptyList()
		}

		showTrailersDialog(api, localTrailers, hiddenTrailers, canSearch)
	}
}

private fun FullDetailsFragment.showTrailersDialog(
	api: ApiClient,
	localTrailers: List<BaseItemDto>,
	hiddenTrailers: HiddenTrailers,
	canSearch: Boolean,
) {
	val context = requireContext()
	val itemId = mBaseItem.id

	val container = LinearLayout(context).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(24.dp(context), 8.dp(context), 24.dp(context), 8.dp(context))
	}
	val dialog = AlertDialog.Builder(context)
		.setTitle(R.string.lbl_trailers)
		.setView(ScrollView(context).apply { addView(container) })
		.create()

	var searching = false

	fun render(focusIndex: Int = 0) {
		// Views that can take the focus: the play button of every trailer, then the search button
		val focusTargets = mutableListOf<View>()
		container.removeAllViews()

		fun addButton(text: String, onClick: () -> Unit) = Button(context).apply {
			this.text = text
			setAllCaps(false)
			isEnabled = !searching
			setOnClickListener { onClick() }
		}

		fun addRow(label: String, onPlay: () -> Unit, onDelete: (() -> Unit)?) {
			val row = LinearLayout(context).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
			}

			val playButton = addButton(label, onPlay).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
			row.addView(playButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
			focusTargets += playButton

			if (onDelete != null) {
				val deleteButton = addButton(getString(R.string.lbl_delete), onDelete)
				row.addView(deleteButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
			}

			container.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
		}

		var number = 0
		fun title(name: String?, suffix: String? = null): String {
			number++
			val base = name?.takeIf { it.isNotBlank() } ?: getString(R.string.lbl_trailer_number, number)
			return if (suffix == null) base else "$base ($suffix)"
		}

		// Local trailers are files of the library: they can only be played
		localTrailers.forEach { trailer ->
			addRow(
				label = title(trailer.name),
				onPlay = {
					dialog.dismiss()
					play(listOf(trailer), 0, false)
				},
				onDelete = null,
			)
		}

		// External trailers: every video once, without the ones the user removed
		val hiddenKeys = hiddenTrailers.get(itemId)
		TrailerUtils.distinctTrailers(mBaseItem.remoteTrailers)
			.filter { TrailerUtils.getTrailerKey(it) !in hiddenKeys }
			.forEach { trailer ->
				val url = requireNotNull(trailer.url)
				val index = focusTargets.size
				val host = TrailerUtils.getExternalTrailerIntent(url).data?.host?.removePrefix("www.")

				addRow(
					label = title(trailer.name, host),
					onPlay = {
						dialog.dismiss()
						openExternalTrailer(TrailerUtils.getExternalTrailerIntent(url))
					},
					onDelete = {
						hiddenTrailers.hide(itemId, TrailerUtils.getTrailerKey(trailer))
						Toast.makeText(context, getString(R.string.msg_trailer_hidden), Toast.LENGTH_SHORT).show()
						// Keep the focus on the row that took the place of the removed one
						render(index)
					},
				)
			}

		if (canSearch) {
			val searchButton = addButton(
				text = getString(if (searching) R.string.msg_trailers_searching else R.string.lbl_trailers_search),
				onClick = {
					searching = true
					render(focusIndex = Int.MAX_VALUE)

					lifecycleScope.launch {
						val message = try {
							val result = api.searchTrailers(itemId, hiddenTrailers.get(itemId))
							mBaseItem = mBaseItem.copy(remoteTrailers = result.trailers)

							// A search puts back the trailers that were removed (by mistake) from the list
							hiddenTrailers.show(itemId, result.restoredKeys)

							buildList {
								if (result.added > 0) {
									add(getString(R.string.msg_trailers_added, result.added, languageName(requireNotNull(result.language))))
								}
								if (result.restoredKeys.isNotEmpty()) {
									add(getString(R.string.msg_trailers_restored, result.restoredKeys.size))
								}
								if (isEmpty()) add(getString(R.string.msg_trailers_none))
							}.joinToString("\n")
						} catch (exception: ApiClientException) {
							Timber.e(exception, "Failed to search trailers for item $itemId")
							getString(R.string.msg_trailers_search_failed)
						}

						searching = false
						Toast.makeText(context, message, Toast.LENGTH_LONG).show()
						render()
					}
				},
			)
			container.addView(searchButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
			focusTargets += searchButton
		}

		focusTargets.getOrNull(focusIndex.coerceIn(0, (focusTargets.size - 1).coerceAtLeast(0)))?.requestFocus()
	}

	render()
	dialog.show()
}

private fun languageName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())

private fun FullDetailsFragment.openExternalTrailer(intent: Intent) {
	try {
		startActivity(intent)
	} catch (exception: ActivityNotFoundException) {
		Timber.w(exception, "Unable to open external trailer")
		Toast.makeText(
			requireContext(),
			getString(R.string.no_player_message),
			Toast.LENGTH_LONG
		).show()
	}
}

/**
 * Read the item with all its fields. Updating an item overwrites its metadata with the sent values, so an update must
 * always start from a complete item and never from one that was loaded with a limited set of fields.
 */
private suspend fun ApiClient.getFullItem(itemId: UUID): BaseItemDto = withContext(Dispatchers.IO) {
	userLibraryApi.getItem(itemId).content
}

private suspend fun ApiClient.setPreferredMetadataLanguage(itemId: UUID, language: String?) {
	val item = getFullItem(itemId)

	withContext(Dispatchers.IO) {
		itemUpdateApi.updateItem(itemId, item.copy(preferredMetadataLanguage = language))
	}
}

private fun trailerKeys(trailers: List<MediaUrl>): Set<String> = trailers.mapNotNullTo(mutableSetOf()) {
	if (it.url.isNullOrBlank()) null else TrailerUtils.getTrailerKey(it)
}

/**
 * Refresh the metadata of the item and wait for the server to add its trailers. Returns the trailers after the refresh.
 */
private suspend fun ApiClient.refreshTrailers(itemId: UUID, before: List<MediaUrl>): List<MediaUrl> {
	val keysBefore = trailerKeys(before)

	withContext(Dispatchers.IO) {
		itemRefreshApi.refreshItem(
			itemId = itemId,
			metadataRefreshMode = MetadataRefreshMode.FULL_REFRESH,
			imageRefreshMode = MetadataRefreshMode.NONE,
			replaceAllMetadata = false,
			replaceAllImages = false,
		)
	}

	var latest = before
	repeat(REFRESH_POLL_ATTEMPTS) {
		delay(REFRESH_POLL_INTERVAL_MS)
		latest = getFullItem(itemId).remoteTrailers.orEmpty()
		if (trailerKeys(latest) != keysBefore) return latest
	}

	return latest
}

/**
 * Search trailers in French, then in English when nothing new was found in French. Like the preferred metadata language
 * of the item is what selects the language of the trailers, it is set before each refresh and restored afterwards.
 * Trailers that are already on the server never count as new, duplicates are ignored.
 *
 * Trailers removed by the user are only hidden on this device: the server keeps them and merges the refresh results into
 * its list, so it can't tell if the search found them again. Every hidden trailer still in the list is restored, which
 * also undoes a removal made by mistake.
 */
private suspend fun ApiClient.searchTrailers(itemId: UUID, hiddenKeys: Set<String>): TrailerSearchResult {
	val original = getFullItem(itemId)
	val knownKeys = trailerKeys(original.remoteTrailers.orEmpty())

	var trailers = original.remoteTrailers.orEmpty()
	var added = 0
	var foundLanguage: String? = null

	try {
		for (language in TRAILER_SEARCH_LANGUAGES) {
			setPreferredMetadataLanguage(itemId, language)
			trailers = refreshTrailers(itemId, trailers)

			added = TrailerUtils.distinctTrailers(trailers).count { TrailerUtils.getTrailerKey(it) !in knownKeys }
			if (added > 0) {
				foundLanguage = language
				break
			}
		}
	} finally {
		// Also when the search failed or was cancelled, the item must not stay in the search language
		withContext(NonCancellable) {
			try {
				setPreferredMetadataLanguage(itemId, original.preferredMetadataLanguage)
			} catch (exception: ApiClientException) {
				Timber.e(exception, "Failed to restore the preferred metadata language of item $itemId")
			}
		}
	}

	val restoredKeys = hiddenKeys intersect trailerKeys(trailers)

	return TrailerSearchResult(trailers, added, foundLanguage, restoredKeys)
}
