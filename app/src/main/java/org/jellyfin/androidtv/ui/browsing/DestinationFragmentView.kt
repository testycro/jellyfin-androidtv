package org.jellyfin.androidtv.ui.browsing

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.os.BundleCompat
import androidx.core.os.ParcelCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import timber.log.Timber
import java.util.Stack

private class HistoryEntry(
	val name: Class<out Fragment>,
	val arguments: Bundle = bundleOf(),

	var fragment: Fragment? = null,
	var savedState: Fragment.SavedState? = null,
) : Parcelable {
	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeString(name.name)
		dest.writeBundle(arguments)
		dest.writeParcelable(savedState, 0)
	}

	companion object CREATOR : Parcelable.Creator<HistoryEntry> {
		@Suppress("UNCHECKED_CAST")
		override fun createFromParcel(parcel: Parcel): HistoryEntry = HistoryEntry(
			name = Class.forName(parcel.readString()!!) as Class<out Fragment>,
			arguments = parcel.readBundle(this::class.java.classLoader)!!,
			fragment = null,
			// Null when the state of the fragment could not be saved
			savedState = ParcelCompat.readParcelable(parcel, this::class.java.classLoader, Fragment.SavedState::class.java),
		)

		override fun newArray(size: Int): Array<HistoryEntry?> = arrayOfNulls(size)
	}
}

class DestinationFragmentView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
	private companion object {
		private const val FRAGMENT_TAG_CONTENT = "content"
		private const val BUNDLE_SUPER = "super"
		private const val BUNDLE_HISTORY = "history"

		/**
		 * Number of screens at the end of the history that stay alive (the current one and the ones before it), next to
		 * the first screen. Every screen in the history keeps its fragment with all loaded items (and some of its
		 * views) in memory, so a long chain like movie > actor > movie > similar movie > ... only kept growing.
		 * Older screens are released and created again when navigating back to them.
		 */
		private const val MAX_ALIVE_FRAGMENTS = 5
	}

	private val fragmentManager by lazy {
		FragmentManager.findFragmentManager(this)
	}

	private val container by lazy {
		FragmentContainerView(context).also { view ->
			view.id = R.id.container
			addView(view)
		}
	}

	private val history = Stack<HistoryEntry>()

	fun navigate(action: NavigationAction.NavigateFragment) {
		val entry = HistoryEntry(action.destination.fragment.java, action.destination.arguments)

		// Create the base transaction so we can mutate everything at once
		val transaction = fragmentManager.beginTransaction()

		if (action.clear) {
			// Clear all current fragments from the history before adding the new entry
			history.mapNotNull { it.fragment }.forEach { transaction.remove(it) }
			history.clear()
			history.push(entry)
		} else if (action.replace && action.addToBackStack && history.isNotEmpty()) {
			// Remove the top-most entry before replacing it with the next
			val currentFragment = history[history.size - 1].fragment
			if (currentFragment != null) transaction.remove(currentFragment)
			history[history.size - 1] = entry
		} else {
			// Add to the end of the history
			saveCurrentFragmentState()
			history.push(entry)
			releaseOldFragments(transaction)
		}

		activateHistoryEntry(entry, transaction)
	}

	fun goBack(): Boolean {
		// Require at least 2 items (current & previous) to go back
		if (history.size < 2) return false

		// Create the base transaction so we can mutate everything at once
		val transaction = fragmentManager.beginTransaction()

		// Remove current entry
		val currentEntry = history.pop()

		// Make sure to remove the associated fragment
		val currentFragment = currentEntry.fragment
		if (currentFragment != null) transaction.remove(currentFragment)

		// Read & set previous entry
		val entry = history.last()
		activateHistoryEntry(entry, transaction)

		return true
	}

	/**
	 * Remove the fragments of the screens deep in the history, see [MAX_ALIVE_FRAGMENTS]. Their history entry stays, a
	 * new fragment is created from its arguments (like the first time it was opened) when navigating back to it.
	 */
	private fun releaseOldFragments(transaction: FragmentTransaction) {
		for (index in 1 until history.size - MAX_ALIVE_FRAGMENTS) {
			val entry = history[index]
			val fragment = entry.fragment ?: continue

			transaction.remove(fragment)
			entry.fragment = null
			entry.savedState = null
		}
	}

	private fun saveCurrentFragmentState() {
		val entry = history.lastOrNull() ?: return

		// Update the top-most history entry with state from current fragment
		// The fragment is missing or not added yet when the transaction showing it is still pending (navigating twice in
		// a row), there is no state to save in that case
		val fragment = entry.fragment ?: fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT) ?: return
		if (!fragment.isAdded) return

		try {
			entry.savedState = fragmentManager.saveFragmentInstanceState(fragment)
		} catch (err: IllegalStateException) {
			Timber.w(err, "Unable to save the state of fragment %s", entry.name.name)
		}
	}

	@SuppressLint("CommitTransaction")
	private fun activateHistoryEntry(
		entry: HistoryEntry,
		transaction: FragmentTransaction,
	) {
		var fragment = entry.fragment

		// Create if there is no existing fragment
		if (fragment == null) {
			fragment = fragmentManager.fragmentFactory.instantiate(context.classLoader, entry.name.name).apply {
				setInitialSavedState(entry.savedState)

				// The arguments never change, they can only be set on a new fragment: setting them on an existing one
				// throws when the state of the activity is already saved (app in the background)
				arguments = entry.arguments
			}
			entry.fragment = fragment
		}

		transaction.apply {
			// Set options
			setReorderingAllowed(true)
			setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)

			// Detach current fragment
			fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)?.let(::detach)

			// Attach or add next fragment
			if (fragment.isDetached) attach(fragment)
			else replace(container.id, fragment, FRAGMENT_TAG_CONTENT)
		}

		if (fragmentManager.isDestroyed) {
			Timber.w("FragmentManager is already destroyed")
		} else if (fragmentManager.isStateSaved) {
			transaction.commitAllowingStateLoss()
		} else {
			transaction.commit()
		}
	}

	override fun onSaveInstanceState(): Parcelable {
		// Always retrieve current state before writing
		saveCurrentFragmentState()

		// Save state
		return bundleOf(
			BUNDLE_SUPER to super.onSaveInstanceState(),
			BUNDLE_HISTORY to ArrayList(history),
		)
	}

	override fun onRestoreInstanceState(state: Parcelable?) {
		// Ignore if not a bundle
		if (state !is Bundle) return super.onRestoreInstanceState(state)

		// Call parent
		@Suppress("DEPRECATION")
		val parent = state.getParcelable<Parcelable>(BUNDLE_SUPER)
		super.onRestoreInstanceState(parent)

		// Restore history
		val savedHistory = BundleCompat.getParcelableArrayList(state, BUNDLE_HISTORY, HistoryEntry::class.java)
		if (savedHistory != null) {
			history.clear()
			history.addAll(savedHistory)
			if (history.isNotEmpty()) activateHistoryEntry(history.last(), fragmentManager.beginTransaction())
		}
	}
}
