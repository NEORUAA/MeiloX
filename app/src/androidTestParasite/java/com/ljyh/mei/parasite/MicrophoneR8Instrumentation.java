package com.ljyh.mei.parasite;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Uses only Android/Java APIs; a debug AndroidX runner cannot link an R8 Kotlin runtime. */
public final class MicrophoneR8Instrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verify();
            result.putString("microphone_boundary", "PASS: manifest ownership, no launcher, untrusted Binder rejection");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("microphone_boundary", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verify() throws Exception {
        Context context = getTargetContext();
        require("com.neoruaa.meilox.parasite".equals(context.getPackageName()), "Wrong target");
        ComponentName component = new ComponentName(context.getPackageName(),
                "com.ljyh.mei.parasite.helper.MicrophoneCaptureService");
        PackageManager manager = context.getPackageManager();
        ServiceInfo service = manager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0));
        require(service.exported && service.getForegroundServiceType() == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                "Module microphone declaration differs");
        String[] permissions = manager.getPackageInfo("com.netease.cloudmusic.tv",
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS)).requestedPermissions;
        require(permissions == null || !Arrays.asList(permissions).contains("android.permission.RECORD_AUDIO"),
                "Host permission metadata changed");
        require(manager.getLaunchIntentForPackage(context.getPackageName()) == null, "Module gained a launcher");
        CountDownLatch ready = new CountDownLatch(1);
        IBinder[] endpoint = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                endpoint[0] = binder;
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        runOnMainSync(() -> require(context.bindService(new Intent().setComponent(component), connection,
                Context.BIND_AUTO_CREATE), "Helper bind failed"));
        try {
            require(ready.await(5, TimeUnit.SECONDS), "Helper bind timed out");
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.neoruaa.meilox.parasite.microphone.v1");
                data.writeString(UUID.randomUUID().toString());
                data.writeInt(3);
                data.writeStrongBinder(new android.os.Binder());
                boolean rejected = false;
                try {
                    require(endpoint[0].transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0), "Protocol missing");
                    reply.readException();
                } catch (IllegalArgumentException error) {
                    rejected = error.getMessage() != null && error.getMessage().contains("Untrusted helper caller");
                }
                require(rejected, "Module UID was allowed to record");
            } finally { data.recycle(); reply.recycle(); }
        } finally { runOnMainSync(() -> context.unbindService(connection)); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
