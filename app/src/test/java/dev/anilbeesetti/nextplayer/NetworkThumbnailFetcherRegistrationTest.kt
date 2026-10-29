package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.toAndroidUri
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientPool
import dev.anilbeesetti.nextplayer.core.media.network.NetworkConnectionResolver
import dev.anilbeesetti.nextplayer.core.media.network.NetworkVideoThumbnailFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coil only calls a [Fetcher.Factory] when the type it was registered for matches the requested
 * data, and Coil hands fetchers a `coil3.Uri` rather than the `android.net.Uri` that was passed to
 * the request, so a factory typed for `android.net.Uri` is never asked for a network URI and the
 * covers never load.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkThumbnailFetcherRegistrationTest {

    private val uri = Uri.parse("smb://server/media/video.mkv?cid=3")

    @Test
    fun coilAsksTheRegisteredFactoryForNetworkUri() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var requestedUri: Uri? = null
        val fetcherFactory = object : Fetcher.Factory<coil3.Uri> {
            override fun create(
                data: coil3.Uri,
                options: Options,
                imageLoader: ImageLoader,
            ): Fetcher {
                requestedUri = data.toAndroidUri()
                return BitmapFetcher()
            }
        }

        val imageLoader = ImageLoader.Builder(context)
            .components { add(fetcherFactory) }
            .build()

        val result = imageLoader.execute(request(context))

        assertTrue(
            "Expected a successful result but was $result",
            result is SuccessResult,
        )
        assertEquals(
            "smb",
            requestedUri?.scheme,
        )
    }

    @Test
    fun coilAsksTheNetworkThumbnailFetcherForNetworkUri() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val fetcher = NetworkVideoThumbnailFetcher(
            connectionResolver = NetworkConnectionResolver { null },
            clientPool = NetworkClientPool(
                clientFactory = NetworkClientFactory { error("No client should be created") },
            ),
        )

        val imageLoader = ImageLoader.Builder(context)
            .components { add(fetcher) }
            .build()

        val result = imageLoader.execute(request(context))

        // The fake resolver returns no connection, so the fetch itself fails, but Coil must have
        // found the factory: a missing factory is reported as its own error.
        assertTrue(
            "Expected a failed fetch but was $result",
            result is ErrorResult,
        )
        val message = (result as ErrorResult).throwable.message.orEmpty()
        assertFalse(
            message,
            message.contains("Unable to create a fetcher"),
        )
    }

    private fun request(context: android.content.Context) = ImageRequest
        .Builder(context)
        .data(uri)
        .build()

    private class BitmapFetcher : Fetcher {
        override suspend fun fetch(): FetchResult = ImageFetchResult(
            image = Bitmap.createBitmap(
                4,
                4,
                Bitmap.Config.ARGB_8888,
            ).asImage(),
            isSampled = true,
            dataSource = DataSource.NETWORK,
        )
    }
}
