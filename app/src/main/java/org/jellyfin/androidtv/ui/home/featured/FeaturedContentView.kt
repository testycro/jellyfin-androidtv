package org.jellyfin.androidtv.ui.home.featured

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import coil3.ImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.FeaturedBarTrailerArea
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.sdk.HiddenTrailers
import org.jellyfin.androidtv.util.sdk.TrailerUtils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The Featured Content Bar: shows the items one after the other and plays their trailer. Left and right go to the
 * previous and next item, select opens the details of the item.
 *
 * Time only goes by while the bar is shown: it stops when it is scrolled away, covered by the screensaver or when an
 * other screen is opened.
 */
@SuppressLint("ViewConstructor")
class FeaturedContentView(context: Context) : FrameLayout(context), KoinComponent {
	private val api by inject<ApiClient>()
	private val userPreferences by inject<UserPreferences>()
	private val imageLoader by inject<ImageLoader>()
	private val navigationRepository by inject<NavigationRepository>()
	private val mediaManager by inject<MediaManager>()
	private val interactionTracker by inject<InteractionTrackerViewModel>()
	private val hiddenTrailers by lazy { HiddenTrailers(context) }

	private val state = FeaturedContentState()
	/**
	 * The trailer fills the bar, or the right half when [trailerSpacer] is shown.
	 */
	private val trailerContainer = LinearLayout(context).apply {
		orientation = LinearLayout.HORIZONTAL
		alpha = 0f
	}
	private val trailerSpacer = View(context).also { spacer ->
		trailerContainer.addView(spacer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
	}

	/**
	 * The items shown so far and the ones coming, [index] is the one that is shown.
	 */
	private val items = mutableListOf<BaseItemDto>()
	private var index = -1
	private var loading = false

	private var scope: CoroutineScope? = null
	private var slideJob: Job? = null

	private var trailer: FeaturedTrailer? = null
	private var trailerEnd: CompletableDeferred<Boolean>? = null
	private var trailerPlayed = false
	private var trailerWaiting = Duration.ZERO

	/**
	 * Whether the bar is shown, time only goes by while it is.
	 */
	private var active = false
	private val visibleRect = Rect()

	init {
		val density = resources.displayMetrics.density
		val barHeight = (resources.displayMetrics.heightPixels * HEIGHT_FRACTION).roundToInt()
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight)
		val horizontalPadding = (HORIZONTAL_PADDING_DP * density).roundToInt()
		val verticalPadding = (VERTICAL_PADDING_DP * density).roundToInt()
		setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
		clipToPadding = false

		// The bar takes the focus as a whole, nothing in it is focusable
		isFocusable = true
		isFocusableInTouchMode = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS

		setOnClickListener { state.item?.let { item -> navigationRepository.navigate(Destinations.itemDetails(item.id)) } }
		setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
		onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
			state.focused = hasFocus
			updateTrailer()
		}

		val maxImageWidth = resources.displayMetrics.widthPixels
		val card = FrameLayout(context).apply {
			clipToOutline = true
			outlineProvider = object : ViewOutlineProvider() {
				override fun getOutline(view: View, outline: Outline) {
					outline.setRoundRect(0, 0, view.width, view.height, FeaturedCornerRadius.value * density)
				}
			}

			addView(ComposeView(context).apply {
				setContent { FeaturedBackdrop(state, api, imageLoader, maxImageWidth) }
			})
			addView(trailerContainer)
			addView(ComposeView(context).apply {
				setContent { FeaturedOverlay(state, api, imageLoader) }
			})
		}
		addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
	}

	/**
	 * Start with the items loaded together with the other rows of the home screen.
	 */
	fun setInitialItems(initialItems: List<BaseItemDto>) {
		if (items.isNotEmpty() || initialItems.isEmpty()) return

		items.addAll(initialItems)
		if (isAttachedToWindow) showItem(0)
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()

		val scope = MainScope()
		this.scope = scope
		scope.launch {
			while (isActive) {
				tick()
				delay(TICK)
			}
		}

		// Start the current item again
		when {
			index >= 0 -> showItem(index)
			items.isNotEmpty() -> showItem(0)
			else -> next()
		}
	}

	override fun onDetachedFromWindow() {
		scope?.cancel()
		scope = null
		slideJob = null
		loading = false
		releaseTrailer()
		active = false

		super.onDetachedFromWindow()
	}

	private fun onKey(keyCode: Int, event: KeyEvent): Boolean {
		val forward = when (keyCode) {
			KeyEvent.KEYCODE_DPAD_RIGHT -> true
			KeyEvent.KEYCODE_DPAD_LEFT -> false
			else -> return false
		}

		// The focus stays on the bar, holding the key doesn't skip a lot of items at once
		if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
			if (forward) next() else previous()
		}

		return true
	}

	private fun previous() {
		if (index > 0) showItem(index - 1)
	}

	private fun next() {
		if (index + 1 < items.size) {
			showItem(index + 1)
			return
		}

		val scope = scope ?: return
		if (loading) return
		loading = true

		scope.launch {
			val loaded = FeaturedContentLoader.load(api, userPreferences)
			loading = false

			// Random items come back, a small library is shown again from the start
			val known = items.mapTo(mutableSetOf()) { it.id }
			items.addAll(loaded.filter { it.id !in known })

			// Only keep the last items to go back to
			val removed = items.size - MAX_HISTORY
			if (removed > 0) {
				repeat(removed) { items.removeAt(0) }
				index = (index - removed).coerceAtLeast(-1)
			}

			when {
				index + 1 < items.size -> showItem(index + 1)
				items.isNotEmpty() -> showItem(0)
				else -> {
					// Server not reachable or nothing to show (yet)
					delay(RETRY_DELAY)
					next()
				}
			}
		}
	}

	private fun showItem(newIndex: Int) {
		slideJob?.cancel()
		releaseTrailer()

		index = newIndex
		val item = items[newIndex]
		state.item = item
		state.canGoBack = newIndex > 0

		val scope = scope ?: return
		slideJob = scope.launch { showSlide(item) }
	}

	private suspend fun showSlide(item: BaseItemDto) {
		if (userPreferences[UserPreferences.featuredBarTrailerAutoplay]) {
			activeDelay(TRAILER_DELAY)

			val source = findTrailer(item)
			if (source != null && playTrailer(source)) {
				next()
				return
			}

			activeDelay(SLIDE_DURATION - TRAILER_DELAY)
		} else {
			activeDelay(SLIDE_DURATION)
		}

		next()
	}

	/**
	 * Wait for [duration], only counting the time the bar is shown.
	 */
	private suspend fun activeDelay(duration: Duration) {
		var remaining = duration
		while (remaining > Duration.ZERO) {
			delay(TICK)
			if (active) remaining -= TICK
		}
	}

	private fun tick() {
		active = isShown &&
			isMostlyVisible() &&
			findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false &&
			!interactionTracker.visible.value

		val trailer = trailer ?: return
		if (active && !trailerPlayed) {
			trailerWaiting += TICK
			if (trailerWaiting > TRAILER_START_TIMEOUT) {
				Timber.w("Trailer of the Featured Content Bar didn't start")
				trailerEnd?.complete(false)
				return
			}
		}

		updateTrailer()
	}

	private fun isMostlyVisible(): Boolean {
		if (height <= 0 || !getGlobalVisibleRect(visibleRect)) return false
		return visibleRect.height() >= height * MIN_VISIBLE_FRACTION
	}

	// Trailers

	private sealed interface TrailerSource {
		data class Local(val url: String) : TrailerSource
		data class YouTube(val videoId: String) : TrailerSource
	}

	/**
	 * The first local trailer, or else the first YouTube trailer the user didn't remove from the list of the item.
	 */
	private suspend fun findTrailer(item: BaseItemDto): TrailerSource? = withContext(Dispatchers.IO) {
		try {
			// The lists of items don't contain the trailers
			val fullItem = api.userLibraryApi.getItem(item.id).content

			if ((fullItem.localTrailerCount ?: 0) > 0) {
				val localTrailer = api.userLibraryApi.getLocalTrailers(item.id).content.firstOrNull()
				if (localTrailer != null) {
					return@withContext TrailerSource.Local(api.videosApi.getVideoStreamUrl(itemId = localTrailer.id, static = true))
				}
			}

			val hiddenKeys = hiddenTrailers.get(item.id)
			TrailerUtils.distinctTrailers(fullItem.remoteTrailers)
				.map(TrailerUtils::getTrailerKey)
				.firstOrNull { key -> key !in hiddenKeys && YOUTUBE_VIDEO_ID.matches(key) }
				?.let { videoId -> TrailerSource.YouTube(videoId) }
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to find the trailer of ${item.id}")
			null
		}
	}

	/**
	 * Play the trailer until it ends.
	 *
	 * @return false when the trailer could not be played.
	 */
	private suspend fun playTrailer(source: TrailerSource): Boolean {
		val trailer = try {
			when (source) {
				is TrailerSource.Local -> LocalFeaturedTrailer(context, source.url, api.accessToken)
				is TrailerSource.YouTube -> YouTubeFeaturedTrailer(context, source.videoId)
			}
		} catch (err: Exception) {
			// The web view can be missing or being updated
			Timber.w(err, "Unable to create the player of the Featured Content Bar")
			return false
		}

		val end = CompletableDeferred<Boolean>()
		this.trailer = trailer
		trailerEnd = end
		trailerPlayed = false
		trailerWaiting = Duration.ZERO
		val rightHalf = userPreferences[UserPreferences.featuredBarTrailerArea] == FeaturedBarTrailerArea.RIGHT_HALF
		trailerSpacer.isVisible = rightHalf
		state.trailerOnRightHalf = rightHalf
		trailerContainer.addView(trailer.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
		updateTrailer()

		val ended = end.await()
		if (!ended) releaseTrailer()
		return ended
	}

	private fun updateTrailer() {
		val trailer = trailer ?: return
		trailer.update(volume = currentVolume(), play = active) { trailerState ->
			// Ignore the answer of a trailer that has been replaced
			if (trailer === this.trailer) onTrailerState(trailerState)
		}
	}

	private fun currentVolume(): Int {
		// Don't play over music
		if (mediaManager.isPlayingAudio) return 0

		val preference = if (hasFocus()) UserPreferences.featuredBarFocusVolume else UserPreferences.featuredBarStartVolume
		return userPreferences[preference]
	}

	private fun onTrailerState(trailerState: FeaturedTrailerState) {
		when (trailerState) {
			FeaturedTrailerState.LOADING -> Unit
			FeaturedTrailerState.PLAYING -> if (!trailerPlayed) {
				// Only show the trailer once it plays, not its loading
				trailerPlayed = true
				state.trailerShown = true
				trailerContainer.animate().alpha(1f).setDuration(TRAILER_FADE.inWholeMilliseconds)
			}

			FeaturedTrailerState.ENDED -> trailerEnd?.complete(true)
			FeaturedTrailerState.ERROR -> trailerEnd?.complete(false)
		}
	}

	private fun releaseTrailer() {
		trailerEnd?.cancel()
		trailerEnd = null

		val trailer = trailer ?: return
		this.trailer = null

		trailerContainer.animate().cancel()
		trailerContainer.alpha = 0f
		state.trailerShown = false
		trailerContainer.removeView(trailer.view)
		trailer.release()
	}

	private companion object {
		const val HEIGHT_FRACTION = 0.5f
		const val HORIZONTAL_PADDING_DP = 24
		const val VERTICAL_PADDING_DP = 8
		const val MIN_VISIBLE_FRACTION = 0.5f
		const val MAX_HISTORY = 50

		val TICK = 500.milliseconds
		val SLIDE_DURATION = 15.seconds
		val TRAILER_DELAY = 3.seconds
		val TRAILER_START_TIMEOUT = 20.seconds
		val TRAILER_FADE = 600.milliseconds
		val RETRY_DELAY = 1.minutes

		val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
	}
}
