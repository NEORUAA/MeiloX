package com.ljyh.mei.standalone

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Frozen v17 entity schema from main at 1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29. */
internal object LegacyStandaloneDatabaseFixture {
    val tables = listOf(
        "color", "song", "like", "qqSong", "playlist", "playback_history", "albums",
        "artists", "album_artist_cross_ref", "cached_lyric", "download_task",
        "playlist_song_cross_ref", "playback_count",
    )
    const val completedId = "17001"
    const val localId = "local_fixture"
    const val streamId = "17002"
    const val contentId = "17003"
    const val contentUri = "content://media/external/audio/media/17003"
    val states = listOf("PENDING", "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED")

    // This fixture never creates a current Room database and downgrades its version.
    fun create(db: SQLiteDatabase) {
        val schema = listOf(
            """CREATE TABLE color (url TEXT NOT NULL, color INTEGER NOT NULL, PRIMARY KEY(url))""",
            """CREATE TABLE song (
                id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL,
                album TEXT NOT NULL, cover TEXT NOT NULL, duration INTEGER NOT NULL,
                path TEXT, sourceType TEXT NOT NULL, fileHash TEXT, fileSize INTEGER NOT NULL,
                fileFormat TEXT, bitrate INTEGER, sampleRate INTEGER, folderPath TEXT,
                addedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))""",
            """CREATE TABLE `like` (id TEXT NOT NULL, PRIMARY KEY(id))""",
            """CREATE TABLE qqSong (
                id TEXT NOT NULL, qid TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL,
                album TEXT NOT NULL, duration INTEGER NOT NULL, PRIMARY KEY(id))""",
            """CREATE TABLE playlist (
                id TEXT NOT NULL, title TEXT NOT NULL, cover TEXT NOT NULL, author TEXT NOT NULL,
                authorName TEXT NOT NULL, authorAvatar TEXT NOT NULL, count INTEGER NOT NULL,
                type TEXT NOT NULL, description TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                playCount INTEGER NOT NULL, lastPlayTime INTEGER NOT NULL, localPlayCount INTEGER NOT NULL,
                PRIMARY KEY(id))""",
            """CREATE TABLE playback_history (
                historyId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                songId TEXT NOT NULL, playedAt INTEGER NOT NULL,
                FOREIGN KEY(songId) REFERENCES song(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE INDEX index_playback_history_songId ON playback_history(songId)",
            """CREATE TABLE albums (
                albumId INTEGER NOT NULL, name TEXT NOT NULL, cover TEXT NOT NULL,
                publishTime INTEGER NOT NULL, songCount INTEGER NOT NULL, PRIMARY KEY(albumId))""",
            """CREATE TABLE artists (
                artistId INTEGER NOT NULL, name TEXT NOT NULL, avatarUrl TEXT, PRIMARY KEY(artistId))""",
            """CREATE TABLE album_artist_cross_ref (
                albumId INTEGER NOT NULL, artistId INTEGER NOT NULL, PRIMARY KEY(albumId, artistId))""",
            "CREATE INDEX index_album_artist_cross_ref_albumId ON album_artist_cross_ref(albumId)",
            "CREATE INDEX index_album_artist_cross_ref_artistId ON album_artist_cross_ref(artistId)",
            """CREATE TABLE cached_lyric (
                songId TEXT NOT NULL, content TEXT NOT NULL, translation TEXT,
                isVerbatim INTEGER NOT NULL, isPureMusic INTEGER NOT NULL, sourceName TEXT NOT NULL,
                parserType TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(songId))""",
            """CREATE TABLE download_task (
                songId TEXT NOT NULL, url TEXT NOT NULL, fileName TEXT NOT NULL, fileType TEXT NOT NULL,
                status TEXT NOT NULL, progress INTEGER NOT NULL, songTitle TEXT NOT NULL,
                songArtist TEXT NOT NULL, songAlbum TEXT NOT NULL, songCover TEXT NOT NULL,
                quality TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                PRIMARY KEY(songId))""",
            """CREATE TABLE playlist_song_cross_ref (
                playlistId TEXT NOT NULL, songId TEXT NOT NULL, sortOrder INTEGER NOT NULL,
                addedAt INTEGER NOT NULL, PRIMARY KEY(playlistId, songId))""",
            "CREATE INDEX index_playlist_song_cross_ref_playlistId ON playlist_song_cross_ref(playlistId)",
            "CREATE INDEX index_playlist_song_cross_ref_songId ON playlist_song_cross_ref(songId)",
            """CREATE TABLE playback_count (
                songId TEXT NOT NULL, playCount INTEGER NOT NULL, lastPlayedAt INTEGER NOT NULL,
                PRIMARY KEY(songId))""",
        )
        schema.forEach(db::execSQL)
        db.version = 17
    }

    fun populate(db: SQLiteDatabase, mediaFile: File) {
        fun song(id: String, source: String, path: String?) {
            db.execSQL(
                "INSERT INTO song VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    id, "Fixture $id", "[\"Artist A\",\"Artist B\"]", "Fixture album",
                    "https://invalid.test/cover", 123456L, path, source,
                    if (path != null) "fixture-hash" else null,
                    if (path != null) mediaFile.length() else 0L,
                    if (path != null) "flac" else null, null, 44100, mediaFile.parent,
                    1700000000000L, 1700000001000L,
                ),
            )
        }
        song(completedId, "DOWNLOAD", mediaFile.path)
        song(localId, "LOCAL", mediaFile.path)
        song(streamId, "STREAM", null)
        // A persisted provider reference is compared as text, never opened or published.
        song(contentId, "DOWNLOAD", contentUri)
        db.execSQL("INSERT INTO color VALUES ('https://invalid.test/cover', -13732729)")
        db.execSQL("INSERT INTO `like` VALUES (?)", arrayOf(completedId))
        db.execSQL("INSERT INTO qqSong VALUES (?, 'fixture-mid', 'QQ title', 'QQ artist', 'QQ album', 123456)", arrayOf(completedId))
        for ((id, type) in listOf("local-list" to "USER", "remote-list" to "NETEAST")) {
            db.execSQL(
                "INSERT INTO playlist VALUES (?, 'Fixture playlist', '', '17', 'Fixture author', '', 2, ?, NULL, 1700000000000, 1700000001000, 123, 1700000002000, 7)",
                arrayOf(id, type),
            )
            db.execSQL("INSERT INTO playlist_song_cross_ref VALUES (?, ?, 2, 1700000001000)", arrayOf(id, completedId))
            db.execSQL("INSERT INTO playlist_song_cross_ref VALUES (?, ?, 1, 1700000001000)", arrayOf(id, localId))
        }
        db.execSQL("INSERT INTO playback_history VALUES (41, ?, 1700000000000)", arrayOf(completedId))
        db.execSQL("INSERT INTO playback_history VALUES (42, ?, 1700000001000)", arrayOf(streamId))
        db.execSQL("INSERT INTO albums VALUES (170, 'Fixture album', '', 1700000000000, 2)")
        db.execSQL("INSERT INTO artists VALUES (171, 'Artist A', NULL), (172, 'Artist B', 'https://invalid.test/artist')")
        db.execSQL("INSERT INTO album_artist_cross_ref VALUES (170, 171), (170, 172)")
        db.execSQL("INSERT INTO cached_lyric VALUES (?, '[00:01.00]Fixture', NULL, 0, 0, 'Netease', 'LRC', 1700000001000)", arrayOf(completedId))
        db.execSQL("INSERT INTO cached_lyric VALUES (?, '[1000,500](0,500,0)Fixture', 'Translation', 1, 0, 'QQ', 'YRC', 1700000001000)", arrayOf(streamId))
        db.execSQL("INSERT INTO playback_count VALUES (?, 9, 1700000001000)", arrayOf(completedId))
        for ((index, status) in states.withIndex()) {
            val id = if (status == "COMPLETED") completedId else "legacy-$status"
            db.execSQL(
                "INSERT INTO download_task VALUES (?, ?, ?, 'flac', ?, ?, 'Fixture title', 'Fixture artist', 'Fixture album', '', 'lossless', ?, ?)",
                arrayOf<Any>(id, "https://invalid.test/legacy/$id", "Fixture $id.flac", status,
                    if (status == "COMPLETED") 100 else index * 10, 1700000000000L + index, 1700000001000L + index),
            )
        }
        db.execSQL(
            "INSERT INTO download_task VALUES (?, '', 'Provider file.flac', 'flac', 'COMPLETED', 100, '', '', '', '', 'lossless', 1700000000000, 1700000001000)",
            arrayOf(contentId),
        )
    }
}
