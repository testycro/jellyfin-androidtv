package org.jellyfin.androidtv.util

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Decoded blurhashes, so an image that is shown again doesn't decode its blurhash again.
 */
object BlurHashCache {
	/**
	 * The decoded blurhashes are tiny (32 pixels wide), the limit is in bytes.
	 */
	@Suppress("MagicNumber")
	private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
		override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
	}

	/**
	 * Get the bitmap of [blurHash], it is decoded when it is not in the cache.
	 */
	fun get(blurHash: String, width: Int, height: Int): Bitmap? {
		val key = "$blurHash:$width:$height"
		val cached = cache.get(key)
		if (cached != null) return cached

		val bitmap = BlurHashDecoder.decode(blurHash, width, height)
		if (bitmap != null) cache.put(key, bitmap)
		return bitmap
	}
}
