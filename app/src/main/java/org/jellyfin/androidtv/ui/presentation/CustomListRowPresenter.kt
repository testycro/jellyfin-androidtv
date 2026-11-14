package org.jellyfin.androidtv.ui.presentation

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.core.view.isVisible
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.SinglePresenterSelector
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.WeakHashMap

/**
 * Number of cards of a row kept bound outside of the screen, in each direction of scrolling.
 */
private const val ROW_ITEM_VIEW_CACHE_SIZE = 10

/**
 * Number of cards of a row prepared ahead of time, about the number of cards visible on screen.
 */
private const val ROW_INITIAL_PREFETCH_COUNT = 8

/**
 * Number of cards of one kind that wait to be used again, for the whole screen.
 */
private const val SHARED_CARD_POOL_SIZE = 30

open class CustomListRowPresenter @JvmOverloads constructor(
	private val topPadding: Int? = null
) : ListRowPresenter() {
	init {
		headerPresenter = CustomRowHeaderPresenter()

		// The cards round their own corners (see ItemCard), the rounded corners of the rows only add an outline and a
		// clip to every card
		enableChildRoundedCorners(false)
	}

	/**
	 * Cards that wait to be used again, one pool for every kind of card, shared by all rows of this screen: a row that
	 * appears takes the cards of the rows that scrolled off instead of creating and composing new ones. One presenter
	 * is used by one screen, so cards are never shared between screens.
	 */
	private val cardPools = mutableMapOf<CardPresenter, RecyclerView.RecycledViewPool>()

	/**
	 * The pool every row was created with, it is used again when the row shows something else than cards.
	 */
	private val defaultPools = WeakHashMap<RecyclerView, RecyclerView.RecycledViewPool>()

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder {
		val holder = super.createRowViewHolder(parent)
		val gridView = (holder as? ViewHolder)?.gridView ?: return holder

		// A row and its cards can be created before the row is added to the screen (see enablePrefetch). The cards are
		// Compose content, which needs to know the screen it is part of. Tell the row now, the list of rows it is created
		// for is on screen. This allows the cards to be composed before the row is shown (see CardPrecomposer).
		parent.findViewTreeCompositionContext()?.let { gridView.compositionContext = it }
		parent.findViewTreeLifecycleOwner()?.let { gridView.setViewTreeLifecycleOwner(it) }
		parent.findViewTreeSavedStateRegistryOwner()?.let { gridView.setViewTreeSavedStateRegistryOwner(it) }

		return holder
	}

	override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
		super.initializeRowViewHolder(holder)

		val rowHolder = holder as? ViewHolder ?: return
		val gridView = rowHolder.gridView

		// Keep more cards bound when they leave the screen (the default is 2): scrolling back shows them right away,
		// without binding them and loading their image again
		gridView.setItemViewCacheSize(ROW_ITEM_VIEW_CACHE_SIZE)

		// Prepare the next card in the spare time between frames when scrolling sideways, and the first cards of the
		// row when the list of rows prepares this row ahead of time (see enablePrefetch). Leanback turns it off.
		gridView.layoutManager?.isItemPrefetchEnabled = true
		gridView.initialPrefetchItemCount = ROW_INITIAL_PREFETCH_COUNT

		// Cards are replaced in the list while it loads and refreshes, the default animations of the list only add work
		// to the frames that scroll
		gridView.itemAnimator = null

		// A row that is on screen and is measuring or laying out its cards needs their real size. A card that is new
		// has no content yet, so no size: the row would start too small and grow a frame later, over the rows below it.
		rowHolder.bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
			override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
				if (!gridView.isAttachedToWindow || !gridView.isLayoutRequested) return

				val card = viewHolder.viewHolder as? PrecomposableCard ?: return
				CardPrecomposer.composeNow(card)
			}
		})
	}

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		val gridView = (holder as? ViewHolder)?.gridView
		val cardPresenter = (item as? ListRow)?.let { row -> cardPresenter(row) }

		if (gridView != null) {
			// A row is used again for other rows, which don't always show cards. The pool of the cards of one row is
			// never left to a row that shows something else.
			val defaultPool = defaultPools.getOrPut(gridView) { gridView.recycledViewPool }
			val pool = cardPresenter?.let { presenter -> cardPool(presenter) } ?: defaultPool

			// Set before the row is filled below, its first cards are then taken from the pool as well
			if (gridView.recycledViewPool !== pool) gridView.setRecycledViewPool(pool)
		}

		super.onBindRowViewHolder(holder, item)

		if (gridView != null && cardPresenter != null && holder is ViewHolder) {
			val pool = gridView.recycledViewPool

			// The number of the kind of view the cards are for the row, known once a row showed a card
			val viewType = holder.bridgeAdapter.presenterMapper?.indexOf(cardPresenter) ?: -1
			if (viewType >= 0) pool.setMaxRecycledViews(viewType, SHARED_CARD_POOL_SIZE)

			CardPrecomposer.scheduleWarmup(holder.bridgeAdapter, gridView, pool, cardPresenter)
		}

		val view = holder.view?.parent as? View ?: return
		if (topPadding != null) view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)

		// Hide header view when the item doesn't have one
		holder.headerViewHolder.view.isVisible = !(item is ListRow && item.headerItem == null)
	}

	/**
	 * The presenter of the cards of [row], null when the row doesn't only show cards.
	 *
	 * Rows only share a pool when all their items are shown by the same [CardPresenter]: the cards of a pool need to be
	 * the same kind of card for every row that uses it.
	 */
	private fun cardPresenter(row: ListRow): CardPresenter? {
		val selector = row.adapter?.presenterSelector as? SinglePresenterSelector ?: return null

		// The selector returns its presenter for every item, including none
		return selector.getPresenter(null) as? CardPresenter
	}

	private fun cardPool(presenter: CardPresenter): RecyclerView.RecycledViewPool = cardPools.getOrPut(presenter) {
		// Number of cards the pool keeps, used by the row that is the first to show a card
		setRecycledPoolSize(presenter, SHARED_CARD_POOL_SIZE)

		RecyclerView.RecycledViewPool()
	}
}
