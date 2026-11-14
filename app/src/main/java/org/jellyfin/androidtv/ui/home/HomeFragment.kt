package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.seconds

class HomeFragment : Fragment() {
	private companion object {
		val KEEP_HOME_FOCUS = 10.seconds
	}

	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()

	/**
	 * The home button got the focus once: this fragment is kept while other screens are opened, its views are created
	 * again when coming back to it.
	 */
	private var homeButtonFocused = false

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val rowsFocusRequester = remember { FocusRequester() }
		val homeFocusRequester = remember { FocusRequester() }
		// The home button has the focus when the screen is opened, the rows when coming back to it
		val focusHomeButton = remember { !homeButtonFocused.also { homeButtonFocused = true } }
		// The toolbar requests the focus for the home button itself, its buttons only exist once it is measured
		LaunchedEffect(rowsFocusRequester) {
			if (!focusHomeButton) rowsFocusRequester.requestFocus()
		}

		// The rows and the Featured Content Bar are loaded after the toolbar and can take the focus while they are, it is
		// given back to the home button until the user presses a key
		val scope = rememberCoroutineScope()
		var keyPressed by remember { mutableStateOf(false) }
		var toolbarFocused by remember { mutableStateOf(false) }
		val keepHomeFocusUntil = remember { SystemClock.uptimeMillis() + KEEP_HOME_FOCUS.inWholeMilliseconds }

		Column(
			modifier = Modifier.onPreviewKeyEvent {
				keyPressed = true
				false
			}
		) {
			Box(
				modifier = Modifier.onFocusChanged { focusState ->
					if (focusState.hasFocus) {
						toolbarFocused = true
					} else if (toolbarFocused) {
						val keepHomeFocus = focusHomeButton && !keyPressed && SystemClock.uptimeMillis() < keepHomeFocusUntil
						if (keepHomeFocus) scope.launch { homeFocusRequester.requestFocus() }
					}
				}
			) {
				MainToolbar(MainToolbarActiveButton.Home, homeFocusRequester, focusHomeButton)
			}

			// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
			// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
			// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
			var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }
			AndroidFragment<HomeRowsFragment>(
				modifier = Modifier
					.focusGroup()
					.focusRequester(rowsFocusRequester)
					.focusProperties {
						onExit = {
							val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
								cancelFocusChange()
							} else {
								rowsSupportFragment?.selectedPosition = 0
								rowsSupportFragment?.verticalGridView?.clearFocus()
							}
						}
					}
					.fillMaxSize(),
				maxLifecycle = Lifecycle.State.RESUMED,
				onUpdate = { fragment ->
					rowsSupportFragment = fragment
				}
			)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}

}
