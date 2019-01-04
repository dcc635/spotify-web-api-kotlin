/* Created by Adam Ratzman (2018) */
package com.adamratzman.spotify.endpoints.client

import com.adamratzman.spotify.endpoints.public.PlaylistsAPI
import com.adamratzman.spotify.main.SpotifyAPI
import com.adamratzman.spotify.main.SpotifyClientAPI
import com.adamratzman.spotify.main.SpotifyRestAction
import com.adamratzman.spotify.main.SpotifyRestActionPaging
import com.adamratzman.spotify.utils.BadRequestException
import com.adamratzman.spotify.utils.EndpointBuilder
import com.adamratzman.spotify.utils.ErrorObject
import com.adamratzman.spotify.utils.PagingObject
import com.adamratzman.spotify.utils.Playlist
import com.adamratzman.spotify.utils.PlaylistURI
import com.adamratzman.spotify.utils.SimplePlaylist
import com.adamratzman.spotify.utils.TrackURI
import com.adamratzman.spotify.utils.UserURI
import com.adamratzman.spotify.utils.encode
import com.adamratzman.spotify.utils.toObject
import com.adamratzman.spotify.utils.toPagingObject
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.function.Supplier
import javax.imageio.IIOException
import javax.imageio.ImageIO
import javax.xml.bind.DatatypeConverter

/**
 * Endpoints for retrieving information about a user’s playlists and for managing a user’s playlists.
 */
class ClientPlaylistAPI(api: SpotifyAPI) : PlaylistsAPI(api) {
    /**
     * Create a playlist for a Spotify user. (The playlist will be empty until you add tracks.)
     *
     * @param user The user’s Spotify user ID.
     * @param name The name for the new playlist, for example "Your Coolest Playlist" . This name does not need to be
     * unique; a user may have several playlists with the same name.
     * @param description
     * @param public Defaults to true . If true the playlist will be public, if false it will be private.
     * To be able to create private playlists, the user must have granted the playlist-modify-private scope.
     * @param collaborative Defaults to false . If true the playlist will be collaborative. Note that to create a
     * collaborative playlist you must also set public to false . To create collaborative playlists you must have
     * granted playlist-modify-private and playlist-modify-public scopes.
     *
     * @return The created [Playlist] object with no tracks
     */
    fun createPlaylist(
        name: String,
        description: String? = null,
        public: Boolean? = null,
        collaborative: Boolean? = null,
        user: String = (api as SpotifyClientAPI).userId
    ): SpotifyRestAction<Playlist> {
        if (name.isEmpty()) throw BadRequestException(ErrorObject(400, "Name cannot be empty"))
        return toAction(Supplier {
            val json = JsonObject()
            json["name"] = name
            if (description != null) json["description"] = description
            if (public != null) json["public"] = public
            if (collaborative != null) json["collaborative"] = collaborative
            post(
                EndpointBuilder("/users/${UserURI(user).id.encode()}/playlists").toString(),
                json.toJsonString()
            ).toObject<Playlist>(api)
        })
    }

    /**
     * Add a track to a user’s playlist.
     *
     * @param playlist the spotify id or uri for the playlist.
     * @param track track id or uri
     * @param position The position to insert the tracks, a zero-based index. For example, to insert the tracks in the
     * first position: position=0; to insert the tracks in the third position: position=2 . If omitted, the tracks will
     * be appended to the playlist. Tracks are added in the order they are listed in the query string or request body.
     *
     * @throws BadRequestException if any invalid track ids is provided or the playlist is not found
     */

    fun addTrackToPlaylist(playlist: String, track: String, position: Int? = null) =
        addTracksToPlaylist(playlist, track, position = position)

    /**
     * Add one or more tracks to a user’s playlist.
     *
     * @param playlist the spotify id or uri for the playlist.
     * @param tracks Spotify track ids. A maximum of 100 tracks can be added in one request.
     * @param position The position to insert the tracks, a zero-based index. For example, to insert the tracks in the
     * first position: position=0; to insert the tracks in the third position: position=2 . If omitted, the tracks will
     * be appended to the playlist. Tracks are added in the order they are listed in the query string or request body.
     *
     * @throws BadRequestException if any invalid track ids is provided or the playlist is not found
     */
    fun addTracksToPlaylist(playlist: String, vararg tracks: String, position: Int? = null): SpotifyRestAction<Unit> {
        val json = JsonObject().apply { this["uris"] = tracks.map { TrackURI(TrackURI(it).id.encode()).uri } }
        if (position != null) json["position"] = position
        return toAction(Supplier {
            post(
                EndpointBuilder("/playlists/${PlaylistURI(playlist).id.encode()}/tracks").toString(),
                json.toJsonString()
            )
            Unit
        })
    }

    /**
     * Change a playlist’s name and public/private state. (The user must, of course, own the playlist.)
     *
     * @param playlist the spotify id or uri for the playlist.
     * @param name Optional. The name to change the playlist to.
     * @param public Optional. Whether to make the playlist public or not.
     * @param collaborative Optional. Whether to make the playlist collaborative or not.
     * @param description Optional. Whether to change the description or not.
     *
     * @throws BadRequestException if the playlist is not found or parameters exceed the max length
     */
    fun changePlaylistDescription(
        playlist: String,
        name: String? = null,
        public: Boolean? = null,
        collaborative: Boolean? = null,
        description: String? = null
    ): SpotifyRestAction<Unit> {
        val json = JsonObject()
        if (name != null) json["name"] = name
        if (public != null) json["public"] = public
        if (collaborative != null) json["collaborative"] = collaborative
        if (description != null) json["description"] = description
        if (json.isEmpty()) throw IllegalArgumentException("At least one option must not be null")
        return toAction(Supplier {
            put(EndpointBuilder("/playlists/${PlaylistURI(playlist).id.encode()}").toString(), json.toJsonString())
            Unit
        })
    }

    /**
     * Get a list of the playlists owned or followed by a Spotify user.
     *
     * @param limit The number of objects to return. Default: 20. Minimum: 1. Maximum: 50.
     * @param offset The index of the first item to return. Default: 0. Use with limit to get the next set of items
     *
     * @throws BadRequestException if the filters provided are illegal
     */
    fun getClientPlaylists(
        limit: Int? = null,
        offset: Int? = null
    ): SpotifyRestActionPaging<SimplePlaylist, PagingObject<SimplePlaylist>> {
        if (limit != null && limit !in 1..50) throw IllegalArgumentException("Limit must be between 1 and 50. Provided $limit")
        if (offset != null && offset !in 0..100000) throw IllegalArgumentException("Offset must be between 0 and 100,000. Provided $limit")
        return toActionPaging(Supplier {
            get(EndpointBuilder("/me/playlists").with("limit", limit).with("offset", offset).toString())
                .toPagingObject<SimplePlaylist>(endpoint = this)
        })
    }

    /**
     * Find a client playlist by its id. Convenience method
     *
     * @param id playlist id or uri
     *
     * @return possibly-null SimplePlaylist
     */
    fun getClientPlaylist(id: String): SpotifyRestAction<SimplePlaylist?> {
        return toAction(Supplier {
            val playlists = getClientPlaylists().complete()
            playlists.items.find { it.id == id } ?: playlists.getAllItems().complete().find { it.id == id }
        })
    }

    /**
     * This method is equivalent to unfollowing a playlist with the given [playlist].
     *
     * Unfortunately, Spotify does not allow **deletion** of playlists themselves
     *
     * @param playlist playlist id
     */
    fun deletePlaylist(playlist: String): SpotifyRestAction<Unit> {
        return (api as SpotifyClientAPI).following.unfollowPlaylist(PlaylistURI(playlist).id)
    }

    /**
     * Reorder a track or a group of tracks in a playlist.
     *
     * When reordering tracks, the timestamp indicating when they were added and the user who added them will be kept
     * untouched. In addition, the users following the playlists won’t be notified about changes in the playlists
     * when the tracks are reordered.
     *
     * @param playlist the spotify id or uri for the playlist.
     * @param reorderRangeStart The position of the first track to be reordered.
     * @param reorderRangeLength The amount of tracks to be reordered. Defaults to 1 if not set.
     * The range of tracks to be reordered begins from the range_start position, and includes the range_length subsequent tracks.
     * Example: To move the tracks at index 9-10 to the start of the playlist, range_start is set to 9, and range_length is set to 2.
     * @param insertionPoint The position where the tracks should be inserted. To reorder the tracks to the end of the playlist, simply set insert_before to the position after the last track.
     * @param snapshotId the playlist snapshot against which to apply this action. **recommended to have**
     *
     * @throws BadRequestException if the playlist is not found or illegal filters are applied
     */
    fun reorderPlaylistTracks(
        playlist: String,
        reorderRangeStart: Int,
        reorderRangeLength: Int? = null,
        insertionPoint: Int,
        snapshotId: String? = null
    ): SpotifyRestAction<Snapshot> {
        return toAction(Supplier {
            val json = JsonObject()
            json["range_start"] = reorderRangeStart
            json["insert_before"] = insertionPoint
            if (reorderRangeLength != null) json["range_length"] = reorderRangeLength
            if (snapshotId != null) json["snapshot_id"] = snapshotId
            put(
                EndpointBuilder("/playlists/${PlaylistURI(playlist).id.encode()}/tracks").toString(),
                json.toJsonString()
            ).toObject<Snapshot>(api)
        })
    }

    /**
     * Replace all the tracks in a playlist, overwriting its existing tracks. This powerful request can be useful
     * for replacing tracks, re-ordering existing tracks, or clearing the playlist.
     *
     * @param playlist the spotify id or uri for the playlist.
     * @param tracks The Spotify track ids.
     *
     * @throws BadRequestException if playlist is not found or illegal tracks are provided
     */
    fun setPlaylistTracks(playlist: String, vararg tracks: String): SpotifyRestAction<Unit> {
        return toAction(Supplier {
            val json = JsonObject()
            json["uris"] = tracks.map { TrackURI(TrackURI(it).id.encode()).uri }
            put(
                EndpointBuilder("/playlists/${PlaylistURI(playlist).id.encode()}/tracks").toString(),
                json.toJsonString()
            )
            Unit
        })
    }

    /**
     * Remove all the tracks in a playlist
     * @param playlist the spotify id or uri for the playlist.
     */
    fun removeAllPlaylistTracks(playlist: String): SpotifyRestAction<Unit> {
        return setPlaylistTracks(playlist)
    }

    /**
     * Replace the image used to represent a specific playlist. Image type **must** be jpeg.
     *
     * Must specify a JPEG image path or image data, maximum payload size is 256 KB
     *
     * @param playlist the spotify id or uri for the playlist.
     * @param imagePath Optionally specify the full local path to the image
     * @param imageUrl Optionally specify a URL to the image
     * @param imageFile Optionally specify the image [File]
     * @param image Optionally specify the image's [BufferedImage] object
     * @param imageData Optionally specify the Base64-encoded image data yourself
     *
     * @throws IIOException if the image is not found
     * @throws BadRequestException if invalid data is provided
     */
    fun uploadPlaylistCover(
        playlist: String,
        imagePath: String? = null,
        imageFile: File? = null,
        image: BufferedImage? = null,
        imageData: String? = null,
        imageUrl: String? = null
    ): SpotifyRestAction<Unit> {
        return toAction(Supplier {
            val data = imageData ?: when {
                image != null -> encode(image)
                imageFile != null -> encode(ImageIO.read(imageFile))
                imageUrl != null -> encode(ImageIO.read(URL(imageUrl)))
                imagePath != null -> encode(ImageIO.read(URL("file:///$imagePath")))
                else -> throw IllegalArgumentException("No cover image was specified")
            }
            put(
                EndpointBuilder("/playlists/${PlaylistURI(playlist).id.encode()}/images").toString(),
                data, contentType = "image/jpeg"
            )
            Unit
        })
    }

    /**
     * Remove a track in the specified positions (zero-based) from the specified playlist.
     *
     * @param playlist the playlist id
     * @param track the track id
     * @param positions the positions at which the track is located in the playlist
     * @param snapshotId the playlist snapshot against which to apply this action. **recommended to have**
     */
    fun removeTrackFromPlaylist(
        playlist: String,
        track: String,
        positions: SpotifyTrackPositions,
        snapshotId: String? = null
    ) = removeTracksFromPlaylist(playlist, track to positions, snapshotId = snapshotId)

    /**
     * Remove all occurrences of a track from the specified playlist.
     *
     * @param playlist the playlist id
     * @param track the track id
     * @param snapshotId the playlist snapshot against which to apply this action. **recommended to have**
     */
    fun removeTrackFromPlaylist(
        playlist: String,
        track: String,
        snapshotId: String? = null
    ) = removeTracksFromPlaylist(playlist, track, snapshotId = snapshotId)

    /**
     * Remove all occurrences of the specified tracks from the given playlist.
     *
     * @param playlist the playlist id
     * @param tracks an array of track ids
     * @param snapshotId the playlist snapshot against which to apply this action. **recommended to have**
     */
    fun removeTracksFromPlaylist(
        playlist: String,
        vararg tracks: String,
        snapshotId: String? = null
    ) = removePlaylistTracksImpl(playlist, tracks.map { it to null }.toTypedArray(), snapshotId)

    /**
     * Remove tracks (each with their own positions) from the given playlist.
     *
     * @param playlist the playlist id
     * @param tracks an array of [Pair]s of track ids *and* track positions (zero-based)
     * @param snapshotId the playlist snapshot against which to apply this action. **recommended to have**
     */
    fun removeTracksFromPlaylist(
        playlist: String,
        vararg tracks: Pair<String, SpotifyTrackPositions>,
        snapshotId: String? = null
    ) = removePlaylistTracksImpl(playlist, tracks.toList().toTypedArray(), snapshotId)

    private fun removePlaylistTracksImpl(
        playlist: String,
        tracks: Array<Pair<String, SpotifyTrackPositions?>>,
        snapshotId: String?
    ): SpotifyRestAction<Snapshot> {
        return toAction(Supplier {
            if (tracks.isEmpty()) throw IllegalArgumentException("You need to provide at least one track to remove")

            val json = JsonObject().apply { if (snapshotId != null) this["snapshot_id"] = snapshotId }

            tracks.map { (track, positions) ->
                JsonObject().apply {
                    this["uri"] = TrackURI(track).uri
                    if (positions?.positions?.isNotEmpty() == true) this["positions"] = positions.positions
                }.also { if (positions?.positions?.isNotEmpty() == true) it["positions"] = positions }
            }.let { json.put("tracks", JsonArray(it)) }
            delete(
                EndpointBuilder("/playlists/${PlaylistURI(playlist).id}/tracks").toString(), body = json.toJsonString()
            ).toObject<Snapshot>(api)
        })
    }

    private fun encode(image: BufferedImage): String {
        val bos = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", bos)
        bos.close()
        return DatatypeConverter.printBase64Binary(bos.toByteArray())
    }

    data class Snapshot(val snapshot_id: String)
}

class SpotifyTrackPositions(vararg val positions: Int) : ArrayList<Int>(positions.toList())
