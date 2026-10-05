package dl;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Compatibility bridge for privacy-instrumented calls in the official URS runtime.
 */
public final class g {
    private g() {
    }

    public static String G(ContentResolver resolver, String name, String ignoredCallSite) {
        return Settings.Secure.getString(resolver, name);
    }

    public static Object J(
            Method method,
            Object receiver,
            Object[] arguments,
            String ignoredCallSite
    ) throws IllegalAccessException, InvocationTargetException {
        return method.invoke(receiver, arguments);
    }

    public static void L(String ignoredCapability, String ignoredCallSite) {
    }

    public static Cursor O(
            ContentResolver resolver,
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArguments,
            String sortOrder,
            String ignoredCallSite
    ) {
        return resolver.query(uri, projection, selection, selectionArguments, sortOrder);
    }

    @SuppressLint({"HardwareIds", "MissingPermission"})
    public static String o(TelephonyManager manager, String ignoredCallSite) {
        return manager.getDeviceId();
    }

    public static byte[] p(NetworkInterface networkInterface, String ignoredCallSite)
            throws SocketException {
        return networkInterface.getHardwareAddress();
    }

    public static String q(InetAddress address, String ignoredCallSite) {
        return address.getHostAddress();
    }

    public static String D(TelephonyManager manager, String ignoredCallSite) {
        return manager.getSimOperatorName();
    }
}
