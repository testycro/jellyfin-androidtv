package org.jellyfin.androidtv.ui.home.featured

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

internal enum class FeaturedTrailerState {
	LOADING,
	PLAYING,
	ENDED,
	ERROR,
}

/**
 * A trailer playing behind the texts of the Featured Content Bar. The trailer is muted until the first update.
 */
internal interface FeaturedTrailer {
	/**
	 * The view showing the trailer, it fills the bar.
	 */
	val view: View

	/**
	 * Apply the wanted volume and play state, then report the state of the trailer. Called regularly while the trailer
	 * is shown.
	 *
	 * @param volume From 0 (muted) to 100.
	 * @param play False to pause the trailer.
	 */
	fun update(volume: Int, play: Boolean, onState: (FeaturedTrailerState) -> Unit)

	fun release()
}

/**
 * A trailer stored next to the item on the server, played by a player of its own (not the player of the app, which
 * would take over the screen).
 */
@OptIn(UnstableApi::class)
internal class LocalFeaturedTrailer(
	context: Context,
	url: String,
	accessToken: String?,
) : FeaturedTrailer {
	private val textureView = TextureView(context)
	override val view: View get() = textureView

	private var videoWidth = 0f
	private var videoHeight = 0f

	private val player: ExoPlayer

	init {
		val httpDataSourceFactory = DefaultHttpDataSource.Factory()
		if (accessToken != null) {
			httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to "MediaBrowser Token=\"$accessToken\""))
		}

		// No audio focus: music that is playing keeps playing, the trailer is muted then
		player = ExoPlayer.Builder(context)
			.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpDataSourceFactory)))
			.build()

		player.volume = 0f
		player.setVideoTextureView(textureView)
		player.addListener(object : Player.Listener {
			override fun onVideoSizeChanged(videoSize: VideoSize) {
				videoWidth = videoSize.width * videoSize.pixelWidthHeightRatio
				videoHeight = videoSize.height.toFloat()
				applyCrop()
			}
		})
		textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCrop() }

		player.setMediaItem(MediaItem.fromUri(url))
		player.prepare()
	}

	/**
	 * Fill the bar with the video, cropping it instead of adding black bars.
	 */
	private fun applyCrop() {
		val viewWidth = textureView.width.toFloat()
		val viewHeight = textureView.height.toFloat()
		if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0f || videoHeight <= 0f) return

		val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
		val matrix = Matrix()
		matrix.setScale(videoWidth * scale / viewWidth, videoHeight * scale / viewHeight, viewWidth / 2f, viewHeight / 2f)
		textureView.setTransform(matrix)
	}

	override fun update(volume: Int, play: Boolean, onState: (FeaturedTrailerState) -> Unit) {
		player.volume = volume.coerceIn(0, 100) / 100f
		player.playWhenReady = play

		onState(
			when {
				player.playerError != null -> FeaturedTrailerState.ERROR
				player.playbackState == Player.STATE_ENDED -> FeaturedTrailerState.ENDED
				player.isPlaying -> FeaturedTrailerState.PLAYING
				else -> FeaturedTrailerState.LOADING
			}
		)
	}

	override fun release() {
		player.release()
		(textureView.parent as? ViewGroup)?.removeView(textureView)
	}
}

/**
 * A YouTube trailer, played by the embedded player of YouTube. YouTube has no player for apps that can be shown behind
 * other content, so the embedded player is loaded in a web view, which is controlled with the API of the player page.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class YouTubeFeaturedTrailer(
	context: Context,
	videoId: String,
) : FeaturedTrailer {
	private val webView = WebView(context)
	override val view: View get() = webView

	private var released = false

	init {
		webView.isFocusable = false
		webView.isFocusableInTouchMode = false
		webView.setBackgroundColor(Color.BLACK)

		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		// Trailers start by themselves
		webView.settings.mediaPlaybackRequiresUserGesture = false

		webView.webViewClient = object : WebViewClient() {
			// Stay on the player, the links of the player (title, logo) are not followed. Only called from Android 7.
			@RequiresApi(Build.VERSION_CODES.N)
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !request.isRedirect
		}
		webView.webChromeClient = object : WebChromeClient() {
			// No grey play icon before the video starts
			override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
		}

		// YouTube refuses to play embedded videos when it doesn't know where they are shown (error 153)
		val origin = "https://${context.packageName}"
		val url = "https://www.youtube.com/embed/$videoId".toUri().buildUpon()
			.appendQueryParameter("autoplay", "1")
			.appendQueryParameter("mute", "1")
			.appendQueryParameter("controls", "0")
			.appendQueryParameter("disablekb", "1")
			.appendQueryParameter("fs", "0")
			.appendQueryParameter("iv_load_policy", "3")
			.appendQueryParameter("rel", "0")
			.appendQueryParameter("playsinline", "1")
			.appendQueryParameter("enablejsapi", "1")
			.appendQueryParameter("origin", origin)
			.appendQueryParameter("widget_referrer", origin)
			.build()
			.toString()

		webView.loadUrl(url, mapOf("Referer" to "$origin/"))
	}

	override fun update(volume: Int, play: Boolean, onState: (FeaturedTrailerState) -> Unit) {
		if (released) return

		webView.evaluateJavascript("$UPDATE_SCRIPT(${volume.coerceIn(0, 100)}, $play)") { result ->
			if (released) return@evaluateJavascript

			onState(
				when (result?.trim('"')) {
					"playing" -> FeaturedTrailerState.PLAYING
					"ended" -> FeaturedTrailerState.ENDED
					"error" -> FeaturedTrailerState.ERROR
					else -> FeaturedTrailerState.LOADING
				}
			)
		}
	}

	override fun release() {
		if (released) return
		released = true

		webView.stopLoading()
		(webView.parent as? ViewGroup)?.removeView(webView)
		webView.destroy()
	}

	private companion object {
		/**
		 * Hide the controls, texts and suggestions of the player and fill the bar with the video (cropped).
		 */
		const val STYLE = ".ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom," +
			".ytp-pause-overlay,.ytp-endscreen-content,.ytp-ce-element,.ytp-watermark,.ytp-spinner," +
			".ytp-cued-thumbnail-overlay,.ytp-large-play-button,.ytp-paid-content-overlay,.ytp-impression-link," +
			".ytp-cards-teaser,.ytp-show-cards-title,.ytp-youtube-button,.ytp-contextmenu{display:none!important}" +
			"html,body,#player,.html5-video-player,.html5-video-container{width:100%!important;height:100%!important;" +
			"background:#000!important;overflow:hidden!important}" +
			"video{width:100%!important;height:100%!important;left:0!important;top:0!important;object-fit:cover!important}"

		/**
		 * Function (volume, play) applying the volume and play state with the API of the player element of the page and
		 * returning "loading", "playing", "ended" or "error".
		 */
		val UPDATE_SCRIPT = "(function (volume, play) {" +
			"if (document.head && !document.getElementById('jellyfin-featured')) {" +
			"var style = document.createElement('style');" +
			"style.id = 'jellyfin-featured';" +
			"style.textContent = '$STYLE';" +
			"document.head.appendChild(style);" +
			"}" +
			"var error = document.querySelector('.ytp-error');" +
			"if (error && error.offsetParent !== null) return 'error';" +
			"var player = document.getElementById('movie_player');" +
			"if (!player || typeof player.getPlayerState !== 'function') return 'loading';" +
			"var state = player.getPlayerState();" +
			"if (state === 0) return 'ended';" +
			"if (volume <= 0) { if (!player.isMuted()) player.mute(); }" +
			"else { if (player.isMuted()) player.unMute(); if (player.getVolume() !== volume) player.setVolume(volume); }" +
			"if (play && state !== 1 && state !== 3) player.playVideo();" +
			"if (!play && (state === 1 || state === 3)) player.pauseVideo();" +
			"return state === 1 ? 'playing' : 'loading';" +
			"})"
	}
}
