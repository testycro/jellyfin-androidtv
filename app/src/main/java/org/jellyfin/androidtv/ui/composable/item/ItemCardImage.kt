package org.jellyfin.androidtv.ui.composable.item

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.getSystemService
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.jellyfin.androidtv.util.BlurHashCache
import kotlin.math.roundToInt

private const val CROSSFADE_DURATION_MS = 100
private const val BLUR_HASH_RESOLUTION = 32

// The blurhash is not shown on devices with little memory, checked once
private var lowRamDevice: Boolean? = null

private fun Context.isLowRamDevice(): Boolean = lowRamDevice
	?: (getSystemService<ActivityManager>()?.isLowRamDevice == true).also { lowRamDevice = it }

private fun createBlurHashPainter(context: Context, blurHash: String?, aspectRatio: Float): Painter? {
	if (blurHash == null || aspectRatio <= 0f || context.isLowRamDevice()) return null

	val width = if (aspectRatio > 1f) (BLUR_HASH_RESOLUTION * aspectRatio).roundToInt() else BLUR_HASH_RESOLUTION
	val height = if (aspectRatio >= 1f) BLUR_HASH_RESOLUTION else (BLUR_HASH_RESOLUTION / aspectRatio).roundToInt()

	return BlurHashCache.get(blurHash, width, height)?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
}

/**
 * The image of an item card. The blurhash is shown until the image is loaded.
 *
 * The image is drawn by Compose itself. Embedding an image view in every card is a lot more work to create and to lay
 * out, and a card is created a lot more often than other content.
 *
 * @param size The size the image is shown in, in pixels. The image is requested right away instead of after the first
 * layout, an image that is in the memory cache is shown in the first frame.
 */
@Composable
fun ItemCardImage(
	url: String,
	blurHash: String?,
	aspectRatio: Float,
	size: IntSize,
	imageLoader: ImageLoader,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
) {
	val context = LocalContext.current

	val request = remember(context, url, size) {
		ImageRequest.Builder(context)
			.data(url)
			.size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
			.crossfade(CROSSFADE_DURATION_MS)
			.build()
	}

	val placeholder = remember(context, blurHash, aspectRatio) { createBlurHashPainter(context, blurHash, aspectRatio) }

	AsyncImage(
		model = request,
		contentDescription = null,
		imageLoader = imageLoader,
		modifier = modifier,
		placeholder = placeholder,
		contentScale = contentScale,
	)
}
