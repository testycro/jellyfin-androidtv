package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Loads the images of cards before they are shown, so they come from the memory cache when the card appears.
 *
 * Images are loaded one at a time, a short while after the selection stopped moving, and a new selection cancels the
 * previous preloading. This keeps the preloading from competing with the scrolling itself.
 *
 * Only items shown by a [CardPresenter] are preloaded, other presenters are ignored.
 */
class CardImagePreloader(
	context: Context,
	private val lifecycleOwner: LifecycleOwner,
) : KoinComponent {
	private companion object {
		val PRELOAD_DELAY = 250.milliseconds

		/** Number of preloaded urls remembered, so they are not requested again. */
		const val MAX_REMEMBERED_URLS = 1_000

		/** Number of cards preloaded after the selected card of a row. */
		const val ROW_AHEAD = 10

		/** Number of cards preloaded at the start of the rows around the selected row. */
		const val OTHER_ROWS = 8

		/** Rows around the selected row that are preloaded, relative to the selected row. */
		val OTHER_ROW_OFFSETS = listOf(1, 2, -1)

		/** Number of cards preloaded after the selected card of a grid. */
		const val GRID_AHEAD = 24

		/** Number of cards preloaded before the selected card of a grid. */
		const val GRID_BEHIND = 8
	}

	private val context = context.applicationContext
	private val imageLoader by inject<ImageLoader>()

	private var preloadJob: Job? = null

	private val preloadedUrls = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > MAX_REMEMBERED_URLS
	}

	/**
	 * Preload the next cards of the row of [item] and the first cards of the rows around it. [rowsAdapter] contains
	 * the rows ([ListRow]) of the screen.
	 */
	fun preloadAroundRow(rowsAdapter: ObjectAdapter?, row: Any?, item: Any?) {
		if (rowsAdapter == null || row !is ListRow || item == null) return
		val rowIndex = rowsAdapter.indexOfItem(row)

		val urls = buildList {
			// Next cards of the current row
			val itemIndex = row.adapter.indexOfItem(item)
			if (itemIndex >= 0) addAll(row.adapter.imageUrls(itemIndex + 1, ROW_AHEAD))

			// First cards of the rows around the current row
			if (rowIndex >= 0) {
				for (offset in OTHER_ROW_OFFSETS) {
					val otherRow = rowsAdapter.getOrNull(rowIndex + offset) as? ListRow ?: continue
					addAll(otherRow.adapter.imageUrls(0, OTHER_ROWS))
				}
			}
		}

		preload(urls)
	}

	/**
	 * Preload the cards around [item] in a grid [adapter], mostly the ones after it.
	 */
	fun preloadAroundGridItem(adapter: ObjectAdapter?, item: Any?) {
		if (adapter == null || item == null) return
		val index = adapter.indexOfItem(item)
		if (index < 0) return

		val urls = adapter.imageUrls(index + 1, GRID_AHEAD) +
			adapter.imageUrls((index - GRID_BEHIND).coerceAtLeast(0), minOf(GRID_BEHIND, index))

		preload(urls)
	}

	fun cancel() {
		preloadJob?.cancel()
	}

	private fun preload(urls: Collection<String>) {
		preloadJob?.cancel()

		val pending = urls.distinct().filter { it !in preloadedUrls }
		if (pending.isEmpty()) return

		preloadJob = lifecycleOwner.lifecycleScope.launch {
			delay(PRELOAD_DELAY)

			for (url in pending) {
				val result = imageLoader.execute(ImageRequest.Builder(context).data(url).build())
				if (result.image != null) preloadedUrls[url] = Unit
			}
		}
	}

	private fun ObjectAdapter.imageUrls(from: Int, count: Int): List<String> =
		(from until minOf(from + count, size())).mapNotNull { index ->
			val item = get(index) ?: return@mapNotNull null
			val presenter = getPresenter(item) as? CardPresenter
			presenter?.getImageUrl(context, item)
		}

	private fun ObjectAdapter.getOrNull(index: Int): Any? = if (index in 0 until size()) get(index) else null

	@Suppress("UNCHECKED_CAST")
	private fun ObjectAdapter.indexOfItem(item: Any): Int {
		if (this is MutableObjectAdapter<*>) return (this as MutableObjectAdapter<Any>).indexOf(item)
		return (0 until size()).firstOrNull { get(it) === item } ?: -1
	}
}
