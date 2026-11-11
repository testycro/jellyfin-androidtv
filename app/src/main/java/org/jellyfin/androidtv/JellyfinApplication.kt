package org.jellyfin.androidtv

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ACRA
import org.jellyfin.androidtv.data.eventhandling.SocketHandler
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.ui.AsyncImageView
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@Suppress("unused")
class JellyfinApplication : Application() {
	override fun onCreate() {
		super.onCreate()

		// Don't run in ACRA service
		if (ACRA.isACRASenderServiceProcess()) return

		val notificationsRepository by inject<NotificationsRepository>()
		notificationsRepository.addDefaultNotifications()
	}

	/**
	 * Called from the StartupActivity when the user session is started.
	 */
	suspend fun onSessionStart() = withContext(Dispatchers.IO) {
		val workManager by inject<WorkManager>()
		val socketListener by inject<SocketHandler>()

		// Update background worker
		launch {
			// Cancel all current workers
			workManager.cancelAllWork().await()

			// Recreate periodic workers
			workManager.enqueueUniquePeriodicWork(
				LeanbackChannelWorker.PERIODIC_UPDATE_REQUEST_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				PeriodicWorkRequestBuilder<LeanbackChannelWorker>(1, TimeUnit.HOURS)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
					.build()
			).await()
		}

		// Update WebSockets
		launch { socketListener.updateSession() }
	}

	/**
	 * Release the memory that is cheap to get back when the system asks for it. The most important case is the app
	 * leaving the screen (an external player or the YouTube app showing a trailer): a background app that holds a lot
	 * of memory is the first one killed when the foreground app needs it, coming back then restarts the app.
	 */
	@Suppress("DEPRECATION") // The "running" levels are not sent anymore on Android 14+, still useful before
	override fun onTrimMemory(level: Int) {
		super.onTrimMemory(level)

		// Don't run in ACRA service
		if (ACRA.isACRASenderServiceProcess()) return

		val imageLoader by inject<ImageLoader>()
		val memoryCache = imageLoader.memoryCache

		when {
			// The device is running low on memory or the app is not visible anymore (UI hidden and all higher levels).
			// Images on screen are still referenced by their views.
			level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
				Timber.i("Trimming memory (level=$level): clearing image caches")
				memoryCache?.clear()
				AsyncImageView.clearBlurHashCache()
			}

			level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
				Timber.i("Trimming memory (level=$level): halving image cache")
				memoryCache?.let { it.trimToSize(it.size / 2) }
			}
		}
	}

	override fun onLowMemory() {
		super.onLowMemory()

		if (ACRA.isACRASenderServiceProcess()) return

		val imageLoader by inject<ImageLoader>()
		imageLoader.memoryCache?.clear()
		AsyncImageView.clearBlurHashCache()
	}

	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)

		TelemetryService.init(this)
	}
}
