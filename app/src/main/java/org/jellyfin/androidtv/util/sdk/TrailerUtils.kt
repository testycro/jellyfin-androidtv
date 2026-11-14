package org.jellyfin.androidtv.util.sdk

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaUrl
import org.jellyfin.sdk.model.api.UserDto

object TrailerUtils {
	/**
	 * Matches the video ID of the various YouTube url forms (watch?v=, youtu.be/, /embed/, /shorts/).
	 */
	private val YOUTUBE_VIDEO_ID_REGEX = Regex("(?:[?&]v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})")

	private const val YOUTUBE_HOST = "youtube.com"
	private const val YOUTUBE_ID_PARAMETER = "v"
	private const val YOUTUBE_URL = "https://youtube.com/watch?v="
	private const val YOUTUBE_ID_LENGTH = 11
	private const val FRAMEWORK_STUB_PACKAGE = "com.android.tv.frameworkpackagestubs"

	@JvmStatic
	fun getExternalTrailerIntent(src: String): Intent {
		val uri = src.toUri()

		// Recreate YouTube urls
		if (uri.host?.endsWith(YOUTUBE_HOST) == true) {
			val id = uri.getQueryParameter(YOUTUBE_ID_PARAMETER).orEmpty()
			if (id.length == YOUTUBE_ID_LENGTH) return Intent(Intent.ACTION_VIEW, "$YOUTUBE_URL$id".toUri())
		}

		return Intent(Intent.ACTION_VIEW, uri)
	}

	/**
	 * An external (remote) trailer with a title that can be shown to the user and an intent that can be handled by an installed app.
	 */
	data class ExternalTrailer(
		val name: String?,
		val intent: Intent,
	)

	/**
	 * Get all remote trailers of the item that can be opened by an installed app.
	 */
	@JvmStatic
	fun getExternalTrailers(context: Context, item: BaseItemDto): List<ExternalTrailer> = item.remoteTrailers.orEmpty()
		.mapNotNull { mediaUrl ->
			val intent = mediaUrl.url?.let(::getExternalTrailerIntent) ?: return@mapNotNull null
			val component = intent.resolveActivity(context.packageManager)

			// Check if there is an activity to handle the intent
			// exclude the FrameworkPackageStubs module, which only displays a message
			// that there is no app to open the intent
			if (component != null && component.packageName != FRAMEWORK_STUB_PACKAGE) ExternalTrailer(mediaUrl.name, intent)
			else null
		}

	/**
	 * Key identifying the video behind a trailer. For YouTube this is the video ID, so http/https, youtu.be and embed
	 * urls of the same video share one key. Other urls use the lowercase url.
	 */
	@JvmStatic
	fun getTrailerKey(trailer: MediaUrl): String {
		val url = trailer.url.orEmpty()
		return YOUTUBE_VIDEO_ID_REGEX.find(url)?.groupValues?.get(1) ?: url.lowercase()
	}

	/**
	 * Remove trailers without url and trailers pointing to a video that is already in the list (the first one is kept).
	 */
	@JvmStatic
	fun distinctTrailers(trailers: List<MediaUrl>?): List<MediaUrl> = trailers.orEmpty()
		.filter { !it.url.isNullOrBlank() }
		.distinctBy { getTrailerKey(it) }

	/**
	 * Searching trailers updates the metadata of the item on the server, which is only allowed for administrators.
	 */
	@JvmStatic
	fun canSearchTrailers(user: UserDto?): Boolean = user?.policy?.isAdministrator == true

	@JvmStatic
	fun getExternalTrailerIntent(context: Context, item: BaseItemDto): Intent? =
		getExternalTrailers(context, item).firstOrNull()?.intent

	@JvmStatic
	fun hasPlayableTrailers(context: Context, item: BaseItemDto): Boolean {
		// Local trailer
		if (item.localTrailerCount != null && item.localTrailerCount!! > 0) return true

		// External trailer
		if (getExternalTrailerIntent(context, item) != null) return true

		// No trailer found
		return false
	}
}
