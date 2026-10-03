package com.ljyh.mei.data.network.sdk;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import java.lang.reflect.Method;

/** Android calls used by the source-based security runtime. */
public final class RuntimeAccess {
    private RuntimeAccess() {}
    public static Object J(Method method, Object receiver, Object[] arguments, String source)
            throws ReflectiveOperationException {
        return method.invoke(receiver, arguments);
    }
    public static String G(ContentResolver resolver, String key, String source) {
        return Settings.Secure.getString(resolver, key);
    }
    public static Cursor O(ContentResolver resolver, Uri uri, String[] projection,
            String selection, String[] args, String order, String source) {
        return resolver.query(uri, projection, selection, args, order);
    }
    public static void L(String event, String source) {}
}
