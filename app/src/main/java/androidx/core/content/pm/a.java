package androidx.core.content.pm;

import android.content.pm.PackageInfo;
import android.os.Build;

/**
 * Binary-compatibility bridge for the obfuscated AndroidX helper used by URS.
 */
public final class a {
    private a() {
    }

    @SuppressWarnings("deprecation")
    public static long a(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }
}
