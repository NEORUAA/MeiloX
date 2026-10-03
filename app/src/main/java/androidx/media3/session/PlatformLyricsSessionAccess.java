package androidx.media3.session;

import androidx.media3.common.util.UnstableApi;

/**
 * Access to the existing platform session for player-owned lyric metadata.
 * Kept in Media3's package because Media3 1.10.1 exposes only the platform token publicly.
 * Direct calls remain R8-safe and make incompatible Media3 upgrades fail at compile time.
 */
@UnstableApi
public final class PlatformLyricsSessionAccess {
    private PlatformLyricsSessionAccess() {}

    public static android.media.session.MediaSession get(MediaSession session) {
        return (android.media.session.MediaSession) session.getImpl()
                .getMediaSessionLegacyStub().getSessionCompat().getMediaSession();
    }
}
