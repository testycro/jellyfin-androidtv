package org.jellyfin.androidtv.data.service

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val imageLoader: ImageLoader,
) {
	companion object {
		val SLIDESHOW_DURATION = 30.seconds
		val TRANSITION_DURATION = 800.milliseconds

		/**
		 * Time the selection must stay on an item before its backdrops are loaded. While scrolling every card gets
		 * selected for a moment, loading (and decoding) its backdrops each time is what made scrolling stutter.
		 */
		val ITEM_LOAD_DELAY = 350.milliseconds
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L

	// Urls of the backgrounds that are shown or being loaded, used to skip loading the same backdrops again
	private var loadedUrls = emptySet<String>()

	// Current background data
	private var _backgrounds = emptyList<ImageBitmap>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurBackground = MutableStateFlow(false)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurBackground get() = _blurBackground.asStateFlow()
	val enabled get() = _enabled.asStateFlow()

	/**
	 * Use all available backdrops from [baseItem] as background.
	 */
	fun setBackground(baseItem: BaseItemDto?) {
		// Check if item is set and backgrounds are enabled
		if (baseItem == null || !userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Enable blur for backdrops
		_blurBackground.value = true

		// Get all backdrop urls, resized by the server: there is no need for more pixels than the screen has
		// (backdrops are often 4K) and a blurred background looks the same at half the resolution
		val displayMetrics = context.resources.displayMetrics
		val scale = if (_blurBackground.value) 2 else 1
		val backdropUrls = (baseItem.itemBackdropImages + baseItem.parentBackdropImages)
			.map { it.getUrl(api, maxWidth = displayMetrics.widthPixels / scale, maxHeight = displayMetrics.heightPixels / scale) }
			.toSet()

		loadBackgrounds(backdropUrls, ITEM_LOAD_DELAY)
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	fun setBackground(server: Server) {
		// Check if item is set and backgrounds are enabled
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds()

		// Disable blur on splashscreen
		_blurBackground.value = false

		// Manually grab the backdrop URL
		val api = jellyfin.createApi(baseUrl = server.address)
		val splashscreenUrl = api.imageApi.getSplashscreenUrl()

		loadBackgrounds(setOf(splashscreenUrl))
	}

	private fun loadBackgrounds(backdropUrls: Set<String>, loadDelay: Duration = Duration.ZERO) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		// Same backdrops as the current ones (e.g. episodes of the same series): keep them
		if (backdropUrls == loadedUrls) return

		// Cancel current loading job
		loadBackgroundsJob?.cancel()
		loadedUrls = backdropUrls
		loadBackgroundsJob = scope.launch {
			// Wait until the selection settles, a new selection cancels this job
			delay(loadDelay)

			// Show the first backdrop as soon as it is loaded
			val first = loadBackground(backdropUrls.first())
			_backgrounds = listOfNotNull(first)
			_currentIndex = 0
			update()

			// The other backdrops are only needed for the slideshow
			val others = backdropUrls.drop(1).mapNotNull { url -> loadBackground(url) }
			if (others.isNotEmpty()) {
				_backgrounds = _backgrounds + others
				if (_backgrounds.size > 1) setTimer()
			}
		}
	}

	private suspend fun loadBackground(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
		imageLoader.execute(
			request = ImageRequest.Builder(context)
				.data(url)
				// The service keeps its own copy of the backdrops. A backdrop is as big as dozens of posters, keeping it
				// in the shared memory cache pushed the posters of the cards out, so they had to be decoded again
				.memoryCachePolicy(CachePolicy.DISABLED)
				.build()
		).image?.toBitmap()?.asImageBitmap()
	}

	fun clearBackgrounds() {
		loadBackgroundsJob?.cancel()
		loadedUrls = emptySet()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		if (_backgrounds.isEmpty()) return

		_backgrounds = emptyList()
		update()
	}

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		_enabled.value = false
	}

	internal fun update() {
		val now = Instant.now().toEpochMilli()
		if (lastBackgroundTimerUpdate > now - TRANSITION_DURATION.inWholeMilliseconds)
			return setTimer((lastBackgroundTimerUpdate - now).milliseconds + TRANSITION_DURATION, false)

		lastBackgroundTimerUpdate = now

		// Get next background to show
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0

		// Set background
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		// Set timer for next background
		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Duration = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) _currentIndex++

			update()
		}
	}
}
