package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkMatchTest {

    private fun file(name: String, isDirectory: Boolean = false) = NetworkFile(
        name = name,
        path = "movies/$name",
        isDirectory = isDirectory,
    )

    @Test
    fun imageNamedLikeTheVideoIsArtwork() {
        assertTrue(file(name = "movie.jpg").isArtworkFor(videoName = "movie.mkv"))
        assertTrue(file(name = "Movie.PNG").isArtworkFor(videoName = "Movie.mkv"))
    }

    @Test
    fun folderArtworkIsArtwork() {
        assertTrue(file(name = "poster.jpg").isArtworkFor(videoName = "movie.mkv"))
        assertTrue(file(name = "folder.png").isArtworkFor(videoName = "movie.mkv"))
        assertTrue(file(name = "cover.webp").isArtworkFor(videoName = "movie.mkv"))
    }

    @Test
    fun unrelatedImagesAreNotArtwork() {
        assertFalse(file(name = "photo.jpg").isArtworkFor(videoName = "movie.mkv"))
        assertFalse(file(name = "movie.jpg").isArtworkFor(videoName = "other.mkv"))
    }

    @Test
    fun nonImagesAreNotArtwork() {
        assertFalse(file(name = "movie.srt").isArtworkFor(videoName = "movie.mkv"))
        assertFalse(file(name = "movie.mkv").isArtworkFor(videoName = "movie.mkv"))
        assertFalse(
            file(
                name = "poster",
                isDirectory = true,
            ).isArtworkFor(videoName = "movie.mkv"),
        )
    }
}
