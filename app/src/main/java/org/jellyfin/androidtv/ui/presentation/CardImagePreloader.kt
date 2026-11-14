package org.jellyfin.androidtv.ui.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
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
	private val api by inject<ApiClient>()

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
			presenter?.getImageUrl(context, api, item)
		}

	private fun ObjectAdapter.getOrNull(index: Int): Any? = if (index in 0 until size()) get(index) else null

	@Suppress("UNCHECKED_CAST")
	private fun ObjectAdapter.indexOfItem(item: Any): Int {
		if (this is MutableObjectAdapter<*>) return (this as MutableObjectAdapter<Any>).indexOf(item)
		return (0 until size()).firstOrNull { get(it) === item } ?: -1
	}
}

/**
 * Number of rows kept bound outside of the screen (the default is 2), so going back up or down shows them right away.
 */
private const val ROWS_VIEW_CACHE_SIZE = 4

/**
 * Let the vertical list of rows prepare the next row (and its first cards, see [CustomListRowPresenter]) in the spare
 * time between frames while scrolling, instead of creating it in the frame where it scrolls into view. Leanback turns
 * this off by default.
 */
fun BaseGridView.enablePrefetch() {
	setItemViewCacheSize(ROWS_VIEW_CACHE_SIZE)
	layoutManager?.isItemPrefetchEnabled = true
	itemAnimator = null
}

/**
 * Number of cards of a grid that wait to be used again, for each kind of card.
 */
private const val GRID_CARD_POOL_SIZE = 30

/**
 * Number of kinds of cards (presenters) a grid uses at most.
 */
private const val GRID_CARD_TYPES = 2

/**
 * Keep the cards that scroll out of a grid instead of throwing most of them away (a list keeps 5 by default, a grid
 * scrolls a whole line of cards at once). A card that is kept still has its content, using it again for another item
 * only updates what changed. A card that was thrown away is created and composed again from scratch.
 */
fun BaseGridView.increaseCardPool() {
	for (viewType in 0 until GRID_CARD_TYPES) recycledViewPool.setMaxRecycledViews(viewType, GRID_CARD_POOL_SIZE)
}

/**
 * Whether [focused] is (in) an item of the first line of the list.
 *
 * The selected position of the list can't be used for this: when the list scrolls it already is the position the list
 * scrolls to, long before that item is shown and focused.
 */
private fun BaseGridView.isOnFirstLine(focused: View, itemsPerLine: Int): Boolean {
	val item = findContainingItemView(focused) ?: return false
	val position = getChildAdapterPosition(item)

	return position != RecyclerView.NO_POSITION && position < itemsPerLine
}

/**
 * Whether the focus has to stay where it is when the list has nothing to focus in [direction] and asks its parent.
 *
 * Going up, the focus is only supposed to leave a list from its first line. The search done by
 * [keepFocusWhenScrollingUp] stops at the parent of the list with this, instead of going on to the views above the list
 * (and to Compose, which hosts the screens) when the list has nothing to focus above an item that is not on its first
 * line.
 *
 * @param focused The view that has the focus.
 * @param itemsPerLine The number of items next to each other in the list, 1 for a list of rows.
 */
@JvmOverloads
fun BaseGridView.keepsFocus(focused: View?, direction: Int, itemsPerLine: Int = 1): Boolean {
	if (direction != View.FOCUS_UP || focused == null) return false

	// The list has the focus itself: the item it selected is not shown (yet)
	if (focused === this) return selectedPosition != RecyclerView.NO_POSITION && (adapter?.itemCount ?: 0) > 0

	// The focus is not in the list
	if (findContainingItemView(focused) == null) return false

	return !isOnFirstLine(focused, itemsPerLine)
}

/**
 * Whether [view] is in this list.
 */
private fun BaseGridView.hasDescendant(view: View): Boolean {
	var parent = view.parent
	while (parent != null) {
		if (parent === this) return true
		parent = parent.parent
	}
	return false
}

/**
 * Go up in a list that scrolls vertically the way Android did before the screens were hosted in Compose.
 *
 * Compose moves the focus itself when the list doesn't use a key: it looks for the closest focusable view in the
 * screen, without asking the list. Leanback never gets to keep the focus while it scrolls, or to go on by itself when
 * the line above is not shown yet. Going up quickly, that line is regularly missing, so the closest view is the toolbar
 * or the title above the list and the focus jumps there, which also stops the scrolling.
 *
 * The list now does the search Android did, and uses the key itself. The key is only left to the screen from the first
 * line, to leave the list as usual.
 *
 * @param itemsPerLine The number of items next to each other in the list, 1 for a list of rows.
 */
@JvmOverloads
fun BaseGridView.keepFocusWhenScrollingUp(itemsPerLine: Int = 1) {
	setOnKeyInterceptListener { event ->
		if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyInterceptListener false

		val focused = findFocus() ?: return@setOnKeyInterceptListener false

		// The list has the focus itself: the item it selected is not shown yet, it gets the focus once it is
		if (focused === this) {
			findViewHolderForAdapterPosition(selectedPosition)?.itemView?.requestFocus(View.FOCUS_UP)
			return@setOnKeyInterceptListener true
		}

		val item = findContainingItemView(focused) ?: return@setOnKeyInterceptListener false
		val position = getChildAdapterPosition(item)

		// From the first line the focus leaves the list
		if (position != RecyclerView.NO_POSITION && position < itemsPerLine) return@setOnKeyInterceptListener false

		// The item above, or the focused item when Leanback keeps the focus (it is scrolling, or it goes on by itself
		// until the line above is shown)
		val next = focused.focusSearch(View.FOCUS_UP)

		if (next != null && next !== focused && hasDescendant(next)) {
			// Same as Android: the item above gets the focus from where the focused item is
			val rect = Rect()
			focused.getFocusedRect(rect)
			offsetDescendantRectToMyCoords(focused, rect)
			offsetRectIntoDescendantCoords(next, rect)
			next.requestFocus(View.FOCUS_UP, rect)
		} else if (next !== focused && scrollState == RecyclerView.SCROLL_STATE_IDLE && position >= itemsPerLine) {
			// Nothing to focus in the list above this item while it is at rest, go to the line above anyway
			setSelectedPositionSmooth(position - itemsPerLine)
		}

		true
	}
}

/**
 * Parent for a list that keeps the focus in the list when going up (see [keepsFocus]). A list that has nothing to
 * focus asks its parent where the focus goes, whether one of its items or the list itself has the focus.
 */
@SuppressLint("ViewConstructor")
class FocusKeepingFrameLayout(
	context: Context,
	private val gridView: BaseGridView,
	private val itemsPerLine: Int,
) : FrameLayout(context) {
	init {
		// Don't change what is drawn: a focused card is larger than its place in the list
		clipChildren = false
		clipToPadding = false
	}

	override fun focusSearch(focused: View?, direction: Int): View? = when {
		gridView.keepsFocus(focused, direction, itemsPerLine) -> focused
		else -> super.focusSearch(focused, direction)
	}
}
