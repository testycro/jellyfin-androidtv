package org.jellyfin.androidtv.ui.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.MessageQueue
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.customview.poolingcontainer.PoolingContainerListener
import androidx.customview.poolingcontainer.addPoolingContainerListener
import androidx.customview.poolingcontainer.removePoolingContainerListener
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.util.getActivity
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * The view that is the item of a list for this view: the view itself or the wrapper a list added around it.
 */
private fun View.listItemRoot(): View {
	var root = this
	while (root.layoutParams !is RecyclerView.LayoutParams) root = root.parent as? View ?: break
	return root
}

/**
 * Whether a list still has this view (or its wrapper) in use for one of its items: shown, or kept bound outside of the
 * screen. False when the view sits unbound in the pool of a list or was thrown away.
 */
private fun View.isBoundListItem(): Boolean {
	val layoutParams = listItemRoot().layoutParams as? RecyclerView.LayoutParams ?: return false

	return try {
		layoutParams.absoluteAdapterPosition != RecyclerView.NO_POSITION
	} catch (err: RuntimeException) {
		// The layout parameters are not linked to a view holder
		Timber.d(err, "Unable to read the position of a card")
		false
	}
}

/**
 * Decides when the composition of a card is thrown away.
 *
 * The default of Compose throws it away as soon as the list of the card leaves the screen. The rows of a screen are
 * lists themselves, so every row that scrolled off the screen lost the compositions of all its cards and every card had
 * to be composed from scratch when the row came back, or when the card was used again for another row. Composing a card
 * from scratch is by far the most expensive part of showing it.
 *
 * This strategy keeps the composition for as long as the card can still be shown without being created again:
 * - a card that is bound to an item keeps it, also while its row is outside of the screen
 * - a card waiting in the pool of its list keeps it, binding it to another item only updates what changed
 * - a card that is thrown away by its list (the pool is full, the list is cleared) loses it
 * - all cards lose it when the screen they are part of is destroyed
 */
internal object CardCompositionStrategy : ViewCompositionStrategy {
	override fun installFor(view: AbstractComposeView): () -> Unit {
		var lifecycle: Lifecycle? = null

		val lifecycleObserver = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_DESTROY) view.disposeComposition()
		}

		val attachListener = object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(v: View) {
				val current = view.findViewTreeLifecycleOwner()?.lifecycle
				if (current === lifecycle) return

				lifecycle?.removeObserver(lifecycleObserver)
				lifecycle = current
				current?.addObserver(lifecycleObserver)
			}

			override fun onViewDetachedFromWindow(v: View) {
				// Nothing would ever dispose the composition without a lifecycle, fall back to the behavior of Compose
				if (lifecycle == null) view.disposeComposition()
			}
		}

		// Called for a card that is thrown away, but also for all cards of a list that leaves the screen
		val poolingContainerListener = PoolingContainerListener {
			if (lifecycle == null || !view.isBoundListItem()) {
				view.disposeComposition()

				// The card is most likely never used again, don't keep it in memory until the screen is destroyed. The
				// lifecycle is found again if the card is added to the screen after all.
				lifecycle?.removeObserver(lifecycleObserver)
				lifecycle = null
			}
		}

		view.addOnAttachStateChangeListener(attachListener)
		view.addPoolingContainerListener(poolingContainerListener)

		return {
			view.removeOnAttachStateChangeListener(attachListener)
			view.removePoolingContainerListener(poolingContainerListener)
			lifecycle?.removeObserver(lifecycleObserver)
			lifecycle = null
		}
	}
}

/**
 * A card whose content can be composed before the card is shown.
 */
internal interface PrecomposableCard {
	val composeView: ComposeView

	/**
	 * Whether the content of the card can be composed before the card is added to the screen: it then doesn't depend
	 * on where the card is added.
	 */
	val canPrecompose: Boolean

	/**
	 * Whether the content of the card is composed. A card that was never shown is not, and a card loses its content
	 * when its list throws it away (see [CardCompositionStrategy]).
	 */
	val isComposed: Boolean
}

/**
 * Gives a card the content of the item it is bound to.
 */
internal fun interface CardUpdate {
	/**
	 * Returns false when the card already shows its item.
	 */
	fun apply(): Boolean
}

/**
 * Does the work for cards that are not shown yet, so it is not left for the frame that shows them.
 *
 * - Compose only composes the content of a view when the view is added to the window, a card that is prepared ahead of
 *   time by its list (prefetch) would still do all its real work in the frame that scrolls it into view. These cards
 *   are composed here, by adding them for a moment to a parent that is never shown.
 * - A card that is used again for another item only updates its content. The updates of the cards that are not shown
 *   are spread over the next frames instead of all landing in the same one.
 * - New cards are created for the pool of a screen while nothing moves, see [scheduleWarmup].
 */
internal object CardPrecomposer {
	/** Set to false to compose all cards when they are shown, as Compose does by default. */
	private const val PRECOMPOSE_ENABLED = true

	/** Set to false to let every list create all the cards it shows itself. */
	private const val WARMUP_ENABLED = true

	private const val NANOS_PER_SECOND = 1_000_000_000L
	private const val NANOS_PER_MILLISECOND = 1_000_000f
	private const val DEFAULT_REFRESH_RATE = 60f

	/** Number of cards created ahead of time for a pool. */
	private const val WARMUP_CARDS = 8

	/** Number of cards created ahead of time in one go, so a device that is slow at it doesn't make the screen late. */
	private const val WARMUP_CARDS_AT_ONCE = 2

	/** Number of frames the screen can stay busy before cards are not created ahead of time anymore. */
	private const val WARMUP_BUSY_FRAMES = 120

	/** Number of cards that are not shown that get their new content in the same frame. */
	private const val UPDATES_AT_ONCE = 2

	/** Expected time to compose a card, until it is measured. */
	private const val INITIAL_COST_NANOS = 6_000_000L

	/** A frame that used less than this part of its time did not draw much: the screen is not moving. */
	private const val IDLE_FRAME_FRACTION = 4

	/** Weight of the last measured time in the expected time to compose a card. */
	private const val COST_SMOOTHING = 4

	/** Number of composed cards between two lines in the log about the time it takes. */
	private const val LOG_INTERVAL = 50

	private val pending = ArrayDeque<PrecomposableCard>()
	private val updates = ArrayDeque<CardUpdate>()
	private val warmups = ArrayDeque<CardPoolWarmup>()
	private var frameCallbackPosted = false
	private var idleHandlerAdded = false
	private var frameTimeNanos = 0L
	private var frameIntervalNanos = 0L
	private var costNanos = INITIAL_COST_NANOS
	private var busyFrames = 0

	// Measured time to compose a card since the last line in the log
	private var measuredCards = 0
	private var measuredNanos = 0L
	private var measuredMaxNanos = 0L

	private val frameCallback = Choreographer.FrameCallback { timeNanos ->
		frameCallbackPosted = false
		frameTimeNanos = timeNanos

		// Cards that were shown, composed or thrown away in the meantime
		pending.removeAll { !it.isWaitingForContent() }

		if (pending.isNotEmpty() || updates.isNotEmpty() || warmups.isNotEmpty()) {
			// Runs when the work of this frame is done
			if (!idleHandlerAdded) {
				idleHandlerAdded = true
				Looper.myQueue().addIdleHandler(idleHandler)
			}

			postFrameCallback()
		}
	}

	private val idleHandler = MessageQueue.IdleHandler {
		idleHandlerAdded = false
		runPendingWork()
		false
	}

	/**
	 * Compose the content of [card], which is prepared by its list but not shown, when there is time for it. Nothing
	 * happens when the card is shown first, its content is then composed as usual.
	 */
	fun schedule(card: PrecomposableCard) {
		if (!PRECOMPOSE_ENABLED || !card.canPrecompose) return

		updateFrameInterval(card.composeView.context)
		if (card !in pending) pending.addLast(card)
		postFrameCallback()
	}

	/**
	 * Compose the content of [card] right away, for a list that is about to measure or show the card and needs its
	 * real size. A card without content has no size: a row measured with such a card started too small and grew a frame
	 * later, over the rows below it.
	 */
	fun composeNow(card: PrecomposableCard) {
		if (!PRECOMPOSE_ENABLED) return

		val host = PrecomposeHost.find(card.composeView) ?: return
		precompose(card, host)
	}

	/**
	 * Run [update], which gives a card that is not shown its new content, in one of the next frames. The card runs the
	 * update itself when it is shown first.
	 */
	fun scheduleUpdate(update: CardUpdate, context: Context) {
		updateFrameInterval(context)
		if (update !in updates) updates.addLast(update)
		postFrameCallback()
	}

	/**
	 * Create cards for [pool] while nothing is moving on screen, so a row that appears finds cards that are ready
	 * instead of creating them in the frame that shows it. Cards are created with [presenter] by [bridgeAdapter], the
	 * adapter of a list using the pool, for [parent], the list itself.
	 */
	fun scheduleWarmup(
		bridgeAdapter: ItemBridgeAdapter,
		parent: ViewGroup,
		pool: RecyclerView.RecycledViewPool,
		presenter: Presenter,
	) {
		if (!WARMUP_ENABLED) return
		if (warmups.any { it.isFor(pool) }) return

		updateFrameInterval(parent.context)
		warmups.addLast(CardPoolWarmup(bridgeAdapter, parent, pool, presenter, WARMUP_CARDS))
		postFrameCallback()
	}

	/**
	 * The time between two frames of the screen, looked up once.
	 */
	private fun updateFrameInterval(context: Context) {
		if (frameIntervalNanos != 0L) return

		val refreshRate = ContextCompat.getDisplayOrDefault(context).refreshRate.takeIf { it >= 1f }
			?: DEFAULT_REFRESH_RATE
		frameIntervalNanos = (NANOS_PER_SECOND / refreshRate).toLong()
	}

	private fun postFrameCallback() {
		if (frameCallbackPosted) return

		frameCallbackPosted = true
		Choreographer.getInstance().postFrameCallback(frameCallback)
	}

	private fun runPendingWork() {
		// The new content of a card is composed in the next frame, a few cards for every frame keeps that frame short
		var updated = 0
		while (updates.isNotEmpty() && updated < UPDATES_AT_ONCE) {
			if (updates.removeFirst().apply()) updated++
		}

		var composed = 0

		while (pending.isNotEmpty()) {
			val start = System.nanoTime()

			// Compose a card when it fits in the time left before the next frame. When the screen is not moving a late
			// frame is not visible, so a device that needs more than a whole frame for a card still gets its cards.
			val fits = frameIntervalNanos - (start - frameTimeNanos) >= costNanos
			if (!fits && !(composed == 0 && isScreenIdle(start))) return

			val card = pending.removeFirst()
			val host = PrecomposeHost.of(card.composeView) ?: continue
			if (precompose(card, host)) composed++
		}

		var created = 0

		while (warmups.isNotEmpty() && created < WARMUP_CARDS_AT_ONCE) {
			val now = System.nanoTime()
			val fits = frameIntervalNanos - (now - frameTimeNanos) >= costNanos

			// Cards are only created ahead of time in the spare time of a frame: a list that is scrolling creates the
			// cards it needs itself and this would only take time away from it. When the screen stays busy the warmups
			// are dropped instead of waking up for every frame, a row that appears asks for cards again.
			if (!fits && !isScreenIdle(now)) {
				busyFrames++
				if (busyFrames > WARMUP_BUSY_FRAMES) {
					busyFrames = 0
					warmups.clear()
				}
				return
			}

			busyFrames = 0
			if (warmups.first().createCard()) created++ else warmups.removeFirst()
		}
	}

	/**
	 * Whether the frame that just passed did little, which means the screen is not moving.
	 */
	private fun isScreenIdle(now: Long): Boolean {
		val elapsed = now - frameTimeNanos
		return elapsed >= 0 && elapsed < frameIntervalNanos / IDLE_FRAME_FRACTION
	}

	private fun precompose(card: PrecomposableCard, host: PrecomposeHost): Boolean {
		if (!card.isWaitingForContent()) return false

		val start = System.nanoTime()
		if (!host.compose(card.composeView.listItemRoot())) return false

		recordCost(System.nanoTime() - start)
		return true
	}

	/**
	 * Remember how long composing a card takes. It is written to the log now and then (tag CardPerf): composing a card
	 * that takes about as long as a frame can't be hidden between two frames, whatever is done here.
	 */
	private fun recordCost(nanos: Long) {
		costNanos += (nanos - costNanos) / COST_SMOOTHING

		measuredCards++
		measuredNanos += nanos
		measuredMaxNanos = maxOf(measuredMaxNanos, nanos)
		if (measuredCards < LOG_INTERVAL) return

		Timber.tag("CardPerf").i(
			"Composing a card takes %.1f ms on average, %.1f ms at most (%d cards). A frame is %.1f ms.",
			measuredNanos / measuredCards / NANOS_PER_MILLISECOND,
			measuredMaxNanos / NANOS_PER_MILLISECOND,
			measuredCards,
			frameIntervalNanos / NANOS_PER_MILLISECOND,
		)

		measuredCards = 0
		measuredNanos = 0L
		measuredMaxNanos = 0L
	}

	/**
	 * A card can be composed here when it is prepared by its list and not shown: not composed and not in any parent.
	 * The screen it is part of needs to be alive.
	 */
	private fun PrecomposableCard.isWaitingForContent(): Boolean = canPrecompose &&
		!isComposed &&
		!composeView.isAttachedToWindow &&
		composeView.listItemRoot().parent == null &&
		composeView.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.CREATED) == true
}

/**
 * Cards created ahead of time for a pool, see [CardPrecomposer.scheduleWarmup].
 */
internal class CardPoolWarmup(
	private val bridgeAdapter: ItemBridgeAdapter,
	parent: ViewGroup,
	private val pool: RecyclerView.RecycledViewPool,
	private val presenter: Presenter,
	private val cards: Int,
) {
	private val parent = WeakReference(parent)

	fun isFor(pool: RecyclerView.RecycledViewPool) = this.pool === pool

	/**
	 * Create one card for the pool. Returns false when the pool has enough cards or when none can be created, the
	 * warmup is then done.
	 */
	fun createCard(): Boolean {
		// A list numbers the kinds of its items in the order it meets their presenters, and the rows of a screen can
		// share that numbering. The number of the cards is only known once a row showed one.
		val viewType = bridgeAdapter.presenterMapper?.indexOf(presenter) ?: -1
		if (viewType < 0) return false

		if (pool.getRecycledViewCount(viewType) >= cards) return false

		val parentView = parent.get() ?: return false
		if (!parentView.isAttachedToWindow) return false

		pool.putRecycledView(bridgeAdapter.createViewHolder(parentView, viewType))

		return true
	}
}

/**
 * Parent used by the [CardPrecomposer]. It is never shown, measured or drawn: a view is only added and removed right
 * away to make Compose create its content.
 */
@SuppressLint("ViewConstructor")
private class PrecomposeHost(context: Context) : ViewGroup(context) {
	init {
		visibility = GONE
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
	}

	/**
	 * Add [view] to the window and remove it again, Compose creates the content of the view when it is added.
	 */
	fun compose(view: View): Boolean {
		if (!isAttachedToWindow || view.parent != null) return false

		try {
			// The view keeps its own layout parameters, they belong to its list (see checkLayoutParams)
			addViewInLayout(view, -1, view.layoutParams ?: generateDefaultLayoutParams(), true)
		} finally {
			if (view.parent === this) removeViewInLayout(view)
		}

		return true
	}

	override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p != null

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

	// The views added here ask for a layout and a redraw of the whole screen, there is nothing to lay out or draw

	@SuppressLint("MissingSuperCall")
	override fun requestLayout() = Unit

	@SuppressLint("MissingSuperCall")
	override fun onDescendantInvalidated(child: View, target: View) = Unit

	companion object {
		/**
		 * The host of the activity showing [view], null when the activity has none yet.
		 */
		fun find(view: View): PrecomposeHost? {
			val content = view.context.getActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: return null

			for (index in 0 until content.childCount) {
				val child = content.getChildAt(index)
				if (child is PrecomposeHost) return child
			}

			return null
		}

		/**
		 * The host of the activity showing [view], it is added to the activity the first time. Not to be used while
		 * a list is laid out, adding a view asks for a new layout of the screen.
		 */
		fun of(view: View): PrecomposeHost? {
			find(view)?.let { host -> return host }

			val content = view.context.getActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: return null
			return PrecomposeHost(content.context).also { host -> content.addView(host, ViewGroup.LayoutParams(0, 0)) }
		}
	}
}
