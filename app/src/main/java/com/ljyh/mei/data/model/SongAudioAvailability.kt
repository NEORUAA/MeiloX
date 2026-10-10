package com.ljyh.mei.data.model

import com.ljyh.mei.constants.MusicQuality

/** Catalog candidates only; playback URL responses still determine account availability. */
fun PlaylistDetail.Playlist.Track.availableMusicQualities(
    privilege: PlaylistDetail.Privilege? = null,
): List<MusicQuality> {
    val flags = privilege?.flag ?: 0
    return buildList {
        if (l?.let { hasAudioResource(it.br, it.fId, it.size.toLong()) } == true) {
            add(MusicQuality.STANDARD)
        }
        if (h?.let { hasAudioResource(it.br, it.fId, it.size.toLong()) } == true) {
            add(MusicQuality.EXHIGH)
        }
        if (sq?.let { hasAudioResource(it.br, it.fId, it.size.toLong()) } == true) {
            add(MusicQuality.LOSSLESS)
        }
        if (hr?.let { hasAudioResource(it.br, it.fId, it.size.toLong()) } == true ||
            flags and HIRES_FLAG != 0
        ) {
            add(MusicQuality.HIRES)
        }
        if (je.hasAudioResource() || flags and SURROUND_FLAG != 0) {
            add(MusicQuality.JYEFFECT)
        }
        if (sk.hasAudioResource() || flags and IMMERSIVE_FLAG != 0) {
            add(MusicQuality.SKY)
        }
        if (jm.hasAudioResource() || flags and MASTER_FLAG != 0) {
            add(MusicQuality.JYMASTER)
        }
        if (dl.hasAudioResource() || flags and DOLBY_FLAG != 0) {
            add(MusicQuality.DOLBY)
        }
    }
}

private fun PlaylistDetail.Playlist.Track.AudioResource?.hasAudioResource(): Boolean =
    this?.let { hasAudioResource(it.br, it.fId, it.size) } == true

private fun hasAudioResource(bitRate: Int, fileId: Long, size: Long): Boolean =
    bitRate > 0 || fileId > 0 || size > 0

private const val HIRES_FLAG = 4_096
private const val DOLBY_FLAG = 16_384
private const val MASTER_FLAG = 65_536
private const val SURROUND_FLAG = 131_072
private const val IMMERSIVE_FLAG = 262_144
