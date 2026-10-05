package kc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Compatibility bridge for broadcast calls rewritten by the official client build.
 */
public final class a {
    private a() {
    }

    public static Intent d(
            Object owner,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String ignoredCallSite
    ) {
        if (!(owner instanceof Context)) {
            return null;
        }
        Context context = (Context) owner;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return context.registerReceiver(receiver, filter);
    }

    public static void h(
            Object owner,
            BroadcastReceiver receiver,
            String ignoredCallSite
    ) {
        if (owner instanceof Context && receiver != null) {
            ((Context) owner).unregisterReceiver(receiver);
        }
    }
}
