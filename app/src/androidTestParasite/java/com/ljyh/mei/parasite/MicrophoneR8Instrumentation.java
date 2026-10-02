package com.ljyh.mei.parasite;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.content.res.XmlResourceParser;
import org.xmlpull.v1.XmlPullParser;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Uses only Android/Java APIs; a debug AndroidX runner cannot link an R8 Kotlin runtime. */
public final class MicrophoneR8Instrumentation extends Instrumentation {
    private boolean pipActions;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        pipActions = arguments != null && "true".equals(arguments.getString("pip_actions"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verify();
            if (pipActions) verifyPipActions();
            result.putString("helper_boundary", "PASS: microphone/PiP ownership, private actions, no launcher, untrusted Binder rejection");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("helper_boundary", "FAIL: " + error);
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
        ActivityInfo pip = manager.getActivityInfo(new ComponentName(context.getPackageName(),
                "com.ljyh.mei.parasite.helper.LyricsPipActivity"), PackageManager.ComponentInfoFlags.of(0));
        require(pip.exported && hasPipActivity(manager, context.getPackageName()), "Module PiP capability differs");
        ActivityInfo receiver = manager.getReceiverInfo(new ComponentName(context.getPackageName(),
                "com.ljyh.mei.parasite.helper.LyricsPipActionReceiver"), PackageManager.ComponentInfoFlags.of(0));
        require(!receiver.exported, "PiP controls are externally exported");
        require(!hasPipActivity(manager, "com.netease.cloudmusic.tv"), "Host PiP metadata changed");
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

    private static boolean hasPipActivity(PackageManager manager, String packageName) throws Exception {
        try (XmlResourceParser xml = manager.getResourcesForApplication(packageName).getAssets()
                .openXmlResourceParser("AndroidManifest.xml")) {
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.getEventType() == XmlPullParser.START_TAG && "activity".equals(xml.getName()) &&
                        xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android",
                                "supportsPictureInPicture", false)) return true;
            }
        }
        return false;
    }

    /** Starts no host activity: the existing TV player menu must launch the helper normally. */
    private void verifyPipActions() throws Exception {
        ActivityMonitor monitor = addMonitor("com.ljyh.mei.parasite.helper.LyricsPipActivity", null, false);
        Bundle ready = new Bundle();
        ready.putString("pip_actions", "READY: open floating lyrics from the TV player menu");
        sendStatus(1, ready);
        Activity activity = waitForMonitorWithTimeout(monitor, 60_000);
        removeMonitor(monitor);
        require(activity != null, "Normal host PiP launch timed out");
        await(() -> activity.isInPictureInPictureMode(), "Helper did not enter pinned mode");
        Method factory = Arrays.stream(activity.getClass().getDeclaredMethods()).filter(method ->
                method.getReturnType() == PictureInPictureParams.class &&
                Arrays.equals(method.getParameterTypes(), new Class<?>[] {boolean.class})).findFirst()
                .orElseThrow(() -> new IllegalStateException("R8 PiP parameter factory is unavailable"));
        factory.setAccessible(true);
        PictureInPictureParams[] params = new PictureInPictureParams[1];
        runOnMainSync(() -> {
            try { params[0] = (PictureInPictureParams) factory.invoke(activity, false); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        require(params[0].getActions().size() == 3, "PiP action count differs");
        for (RemoteAction action : params[0].getActions()) require(
                "com.neoruaa.meilox.parasite".equals(action.getActionIntent().getCreatorPackage()) &&
                action.getActionIntent().isImmutable(), "PiP action ownership differs");
        dispatch(params[0], 1, "play");
        dispatch(params[0], 2, "next");
        dispatch(params[0], 0, "previous");
        dispatch(params[0], 1, "pause");
        runOnMainSync(activity::finish);
        await(activity::isDestroyed, "Helper did not finish");
        dispatch(params[0], 1, "closed_stale");
        Bundle status = new Bundle();
        status.putString("pip_actions", "PASS: installed R8 helper, pinned mode, immutable action ownership and dispatch; verify host state separately");
        sendStatus(2, status);
    }

    private void dispatch(PictureInPictureParams params, int index, String stage) throws Exception {
        params.getActions().get(index).getActionIntent().send();
        Bundle status = new Bundle();
        status.putString("pip_stage", stage);
        sendStatus(2, status);
        Thread.sleep(4_000);
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        require(condition.getAsBoolean(), message);
    }
}
