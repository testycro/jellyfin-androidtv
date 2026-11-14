package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.ImageLoader
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemCardBaseItemOverlay
import org.jellyfin.androidtv.ui.composable.item.ItemCardImage
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CardPresenter(
	val showInfo: Boolean,
	val imageType: ImageType,
	val staticHeight: Int,
	val uniformAspect: Boolean,
) : Presenter() {
	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int) : this(showInfo, imageType, staticHeight, false)
	constructor(showInfo: Boolean, staticHeight: Int) : this(showInfo, ImageType.POSTER, staticHeight)
	constructor(showInfo: Boolean) : this(showInfo, 150)
	constructor() : this(true)

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		// The parent can be a row that is not added to the screen yet, the row then knows where it will be added (see
		// CustomListRowPresenter)
		val compositionContext = parent.findViewTreeCompositionContext()
		val lifecycleOwner = parent.findViewTreeLifecycleOwner()
		val savedStateRegistryOwner = parent.findViewTreeSavedStateRegistryOwner()

		val view = ComposeView(parent.context).apply {
			setViewCompositionStrategy(CardCompositionStrategy)
			setParentCompositionContext(compositionContext)
			setViewTreeLifecycleOwner(lifecycleOwner)
			setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
			isFocusable = true
			isFocusableInTouchMode = true

			setOnLongClickListener {
				context.getActivity()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
			}
		}

		// The content can only be composed before the card is shown when it doesn't depend on where the card is added
		val canPrecompose = compositionContext != null && lifecycleOwner != null && savedStateRegistryOwner != null

		return CardViewHolder(view, canPrecompose)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder) return
		if (item !is BaseRowItem) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return

		viewHolder.unbind()
	}

	/**
	 * Url of the image the card of [item] shows, exactly as the card requests it so a preloaded image is a cache hit.
	 */
	fun getImageUrl(context: Context, api: ApiClient, item: Any?): String? {
		if (item !is BaseRowItem) return null
		return item.getCardImage(imageType, uniformAspect, staticHeight).getUrl(api, Density(context))
	}

	private inner class CardViewHolder(
		override val composeView: ComposeView,
		override val canPrecompose: Boolean,
	) : ViewHolder(composeView), PrecomposableCard {
		// Items are equal when they have the same id. An item that is replaced by a newer version of itself (it was
		// played, its progress changed) still needs to update the card, so every new instance counts as a change.
		private val _item = mutableStateOf<BaseRowItem?>(null, referentialEqualityPolicy())

		// Only handed down as state, so a focus change recomposes the texts of the card and not the whole card
		private val _focused = mutableStateOf(false)

		// The item the card is bound to. The content shows it once it is given to _item, see bind
		private var boundItem: BaseRowItem? = null

		// The item the content was last composed for
		private var composedItem: BaseRowItem? = null

		private val showBoundItem = CardUpdate {
			if (_item.value === boundItem) {
				// The card was hidden for an item it didn't get in the end: its content already is the one of its item
				if (isComposed && composedItem === boundItem) composeView.alpha = 1f
				return@CardUpdate false
			}

			_item.value = boundItem
			if (!view.isAttachedToWindow) invalidateSize()
			true
		}

		override var isComposed = false
			private set

		init {
			composeView.setContent {
				val item = _item.value

				DisposableEffect(Unit) {
					isComposed = true
					onDispose { isComposed = false }
				}

				// The content is composed for the current item, the card can be shown (see bind)
				SideEffect {
					composedItem = item
					composeView.alpha = 1f
				}

				CardViewHolderContent(
					item = item,
					focused = _focused,
					showInfo = showInfo,
					imageType = imageType,
					staticHeight = staticHeight,
					uniformAspect = uniformAspect,
				)
			}

			// A card that is added to the screen shows its item, also when its turn to be updated didn't come yet
			composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
				override fun onViewAttachedToWindow(v: View) {
					showBoundItem.apply()
				}
				override fun onViewDetachedFromWindow(v: View) = Unit
			})

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = { _, focused -> _focused.value = focused }
		}

		fun bind(item: BaseRowItem) {
			val changed = boundItem !== item
			boundItem = item
			_focused.value = view.isFocused

			// A card that is on screen is updated in place
			if (view.isAttachedToWindow) {
				showBoundItem.apply()
				return
			}

			if (changed) {
				// The content of a card that is used again for another item is only updated in a next frame. Hide the
				// card until then, so it never shows its previous item when it is added to the screen in this frame.
				view.alpha = 0f
				invalidateSize()
			}

			if (!isComposed) {
				// A card that has no content yet gets it when it is added to the screen, which is the most expensive part
				// of showing a card. When the card is prepared ahead of time, compose it ahead of time as well. A row
				// that needs the size of the card right now composes it right after this (see CustomListRowPresenter).
				_item.value = item
				CardPrecomposer.schedule(this)
			} else if (changed) {
				// The card is prepared ahead of time, a few of those cards get their new content in every frame instead
				// of all at once
				CardPrecomposer.scheduleUpdate(showBoundItem, view.context)
			}
		}

		fun unbind() {
			// The item is kept on purpose: clearing it removed the image view from the composition, so the next bind
			// had to create the image view again and restart the image loading from scratch
			_focused.value = false
		}

		/**
		 * Make the list measure the card again the next time it uses it. The content of a card that is not on screen
		 * can change without the card asking for it, the list would then keep using the size of the previous content.
		 */
		private fun invalidateSize() {
			composeView.forceLayout()
			composeView.getChildAt(0)?.forceLayout()
		}
	}
}

private data class BaseRowItemDisplayConfig(
	val image: JellyfinImage?,
	val iconRes: Int,
	val aspectRatio: Float,
	val overrideShowInfo: Boolean? = null,
	val scaleType: ImageView.ScaleType? = null,
)

private fun BaseRowItem.getDisplayConfig(imageType: ImageType, uniformAspect: Boolean): BaseRowItemDisplayConfig = when (baseRowType) {
	BaseRowType.BaseItem -> {
		val preferSeriesPoster = this is BaseItemDtoBaseRowItem && preferSeriesPoster
		val primaryAspectRatio = baseItem?.primaryImageAspectRatio?.toFloat()
		val defaultAspectRatio = when {
			preferParentThumb && (baseItem?.parentThumbItemId != null || baseItem?.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.EPISODE && primaryAspectRatio != null -> primaryAspectRatio
			baseItem?.type == BaseItemKind.EPISODE && (baseItem.parentThumbItemId != null || baseItem.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.USER_VIEW -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> primaryAspectRatio ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		}

		val base = BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
				ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				else -> defaultAspectRatio
			},
			image = getImage(imageType),
			iconRes = R.drawable.ic_clapperboard,
		)

		when (baseItem?.type) {
			BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> base.copy(
				iconRes = R.drawable.ic_music_album,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.PERSON,
			BaseItemKind.MUSIC_ARTIST -> base.copy(
				iconRes = R.drawable.ic_user,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.SEASON, BaseItemKind.SERIES -> base.copy(
				aspectRatio = if (imageType == ImageType.POSTER) ImageHelper.ASPECT_RATIO_2_3.toFloat() else base.aspectRatio,
				iconRes = R.drawable.ic_tv
			)

			BaseItemKind.EPISODE -> when (preferSeriesPoster) {
				true -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat(),
					iconRes = R.drawable.ic_tv
				)

				false -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
					iconRes = R.drawable.ic_tv,
					overrideShowInfo = true,
				)
			}

			BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> base.copy(
				aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.FOLDER, BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE -> base.copy(
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.PHOTO -> base.copy(
				iconRes = R.drawable.ic_photo
			)

			BaseItemKind.PHOTO_ALBUM, BaseItemKind.PLAYLIST -> base.copy(
				iconRes = R.drawable.ic_folder
			)

			BaseItemKind.MOVIE, BaseItemKind.VIDEO -> base.copy(
				aspectRatio = when (imageType) {
					ImageType.POSTER -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
					else -> base.aspectRatio
				},
				iconRes = R.drawable.ic_clapperboard,
			)

			else -> base
		}
	}

	BaseRowType.LiveTvChannel -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: 1f
		},
		image = getImage(imageType),
		scaleType = ImageView.ScaleType.FIT_CENTER,
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.LiveTvProgram -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_tv,
		overrideShowInfo = true,
	)

	BaseRowType.LiveTvRecording -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.SeriesTimer -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		iconRes = R.drawable.ic_tv_timer,
		image = getImage(imageType),
		overrideShowInfo = true,
	)

	BaseRowType.Person -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_user,
	)

	BaseRowType.Chapter -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)

	BaseRowType.GridButton -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)
}

/**
 * Dependencies of the cards, looked up once instead of for every card.
 */
private object CardDependencies : KoinComponent {
	val api by inject<ApiClient>()
	val imageLoader by inject<ImageLoader>()
}

/**
 * The title of the focused card only starts scrolling after this delay, so moving quickly over cards doesn't start
 * (and stop) a marquee animation for every card.
 */
private const val MARQUEE_DELAY_MS = 1_000

private class CardImage(
	val displayConfig: BaseRowItemDisplayConfig,
	val aspectRatio: Float,
	val size: DpSize,
) {
	fun getUrl(api: ApiClient, density: Density): String? = displayConfig.image?.getUrl(
		api,
		maxWidth = with(density) { size.width.roundToPx() },
		maxHeight = with(density) { size.height.roundToPx() },
	)
}

private fun BaseRowItem.getCardImage(imageType: ImageType, uniformAspect: Boolean, cardHeight: Int): CardImage {
	val displayConfig = getDisplayConfig(imageType, uniformAspect)
	val aspectRatio = displayConfig.aspectRatio.takeIf { it >= 0.1f }
		?: displayConfig.image?.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

	val size = when (staticHeight) {
		true -> DpSize(cardHeight.dp * aspectRatio, cardHeight.dp)
		false if (aspectRatio > 1f) -> DpSize(130.dp * aspectRatio, 130.dp)
		else -> DpSize(150.dp * aspectRatio, 150.dp)
	}

	return CardImage(displayConfig, aspectRatio, size)
}

/**
 * Single line text of a card. The text only scrolls while the card has focus, this is the only moment the text needs
 * the offscreen layer that is required to fade out the edges of the scrolling text.
 */
@Composable
private fun CardText(
	text: String,
	focused: State<Boolean>,
	modifier: Modifier = Modifier,
	color: Color = Color.Unspecified,
	padding: Dp = 0.dp,
) {
	val isFocused = focused.value
	val style = LocalTextStyle.current

	val textModifier = if (isFocused) {
		Modifier
			.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = MARQUEE_DELAY_MS)
			.padding(padding)
			.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
	} else {
		Modifier.padding(padding)
	}

	BasicText(
		text = text,
		modifier = modifier.then(textModifier),
		style = style.merge(
			color = color.takeOrElse { style.color.takeOrElse { Color.Black } },
			textAlign = TextAlign.Center,
		),
		overflow = TextOverflow.Ellipsis,
		maxLines = 1,
	)
}

/**
 * Key of the values that are remembered for an item. Items are equal when they have the same id, also when neither has
 * one, so the item itself can't be the key: a card that is used again for such an item would keep the name and the
 * image of its previous item.
 */
private class ItemKey(private val item: BaseRowItem?) {
	override fun equals(other: Any?) = other is ItemKey && other.item === item
	override fun hashCode() = System.identityHashCode(item)
}

@Composable
private fun CardFooter(
	text: String,
	focused: State<Boolean>,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.6f), JellyfinTheme.shapes.extraSmall),
	) {
		CardText(
			text = text,
			focused = focused,
			color = Tokens.Color.colorWhite,
			padding = Tokens.Space.spaceXs,
		)
	}
}

@Composable
@Stable
private fun CardViewHolderContent(
	item: BaseRowItem?,
	focused: State<Boolean>,
	showInfo: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	uniformAspect: Boolean,
) {
	val context = LocalContext.current
	val localDensity = LocalDensity.current

	val itemKey = ItemKey(item)
	val title = remember(itemKey, context) { item?.getCardName(context) }
	val subtitle = remember(itemKey, context) { item?.getSubText(context) }
	val cardImage = remember(itemKey, imageType, uniformAspect, staticHeight) { item?.getCardImage(imageType, uniformAspect, staticHeight) }
	if (item == null || cardImage == null) return

	val displayConfig = cardImage.displayConfig
	val image = displayConfig.image
	val aspectRatio = cardImage.aspectRatio
	val size = cardImage.size

	val usePreview = displayConfig.overrideShowInfo ?: showInfo

	val card = @Composable {
		ItemCard(
			image = {
				if (image != null) {
					val url = remember(cardImage, localDensity) { cardImage.getUrl(CardDependencies.api, localDensity) }
					val imageSize = remember(size, localDensity) {
						with(localDensity) { IntSize(size.width.roundToPx(), size.height.roundToPx()) }
					}

					if (url != null) {
						ItemCardImage(
							url = url,
							blurHash = image.blurHash,
							aspectRatio = aspectRatio,
							size = imageSize,
							imageLoader = CardDependencies.imageLoader,
							contentScale = when (displayConfig.scaleType) {
								ImageView.ScaleType.FIT_CENTER -> ContentScale.Fit
								else -> ContentScale.Crop
							},
							modifier = Modifier
								.fillMaxSize()
						)
					}
				} else if (item is GridButtonBaseRowItem && item.gridButton.imageRes != null) {
					Image(
						painter = painterResource(item.gridButton.imageRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize()
					)
				} else {
					Image(
						painter = painterResource(displayConfig.iconRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center)
					)
				}
			},
			overlay = {
				val showInfo = !usePreview && item.showCardInfoOverlay
				item.baseItem?.let { baseItem ->
					ItemCardBaseItemOverlay(
						item = baseItem,
						// No footer at all when there is nothing to show in it, the overlay then adds no layout for it
						footer = if (showInfo && title != null) {
							{ CardFooter(text = title, focused = focused) }
						} else {
							null
						},
					)
				}
			},
			modifier = Modifier
				.size(size)
		)
	}

	if (usePreview) {
		ItemPreview(
			card = { card() },
			title = title?.let { text -> { CardText(text = text, focused = focused) } },
			subtitle = subtitle?.let { text -> { CardText(text = text, focused = focused) } },
			cardWidth = size.width,
		)
	} else {
		card()
	}
}
