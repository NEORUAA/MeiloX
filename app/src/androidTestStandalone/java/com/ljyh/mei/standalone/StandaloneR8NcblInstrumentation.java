package com.ljyh.mei.standalone;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import dalvik.system.PathClassLoader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Executes the installed R8 codec without linking debug AndroidX or application classes. */
public final class StandaloneR8NcblInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public Application newApplication(ClassLoader loader, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        // Encoding needs no application graph, persisted account, workers or network initialization.
        return super.newApplication(loader, "android.app.Application", context);
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            verifyCodec();
            results.putString("r8_ncbl", "PASS: 32 native compressions and 32 NCBL envelopes");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("r8_ncbl", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void verifyCodec() throws Exception {
        Context target = getTargetContext();
        require("com.neoruaa.meilox.standalone.debug".equals(target.getPackageName()),
                "Only the isolated standalone test installation is supported");
        String apk = target.getApplicationInfo().sourceDir;
        require(required("apkSha256").equals(sha256(apk)), "Target APK fingerprint differs");
        try (ZipFile contents = new ZipFile(apk)) {
            require(contents.getEntry("lib/arm64-v8a/libzstd-jni-1.5.7-20.so") != null,
                    "The tested APK must contain the pinned arm64 Zstd library");
        }

        // A fresh target-only loader cannot substitute classes or native libraries from the test APK.
        String nativePath = apk + "!/lib/arm64-v8a" + File.pathSeparator
                + target.getApplicationInfo().nativeLibraryDir;
        ClassLoader loader = new PathClassLoader(apk, nativePath, ClassLoader.getSystemClassLoader());
        Class<?> codec = Class.forName(required("codecClass"), true, loader);
        require(codec.getClassLoader() == loader, "Codec was not loaded from the target APK");
        Object instance = codec.getDeclaredField(required("codecInstance")).get(null);
        Method encode = codec.getDeclaredMethod(required("codecEncode"), byte[].class, byte[].class);
        Class<?> compressor = Class.forName("com.github.luben.zstd.ZstdCompressCtx", true, loader);
        require(compressor.getClassLoader() == loader, "Zstd was not loaded from the target APK");
        Method compress = compressor.getDeclaredMethod(required("compressMethod"), byte[].class);
        byte[] record = "1000\u0001_plv\u0001{\"id\":123,\"time\":0}".getBytes(StandardCharsets.UTF_8);
        byte[] meta = "{}".getBytes(StandardCharsets.UTF_8);
        Set<String> uuids = new HashSet<>();
        for (int index = 0; index < 32; index++) {
            try (Closeable context = (Closeable) compressor.getDeclaredConstructor().newInstance()) {
                byte[] compressed = (byte[]) compress.invoke(context, record);
                require(compressed.length > 4, "Native compression returned no frame");
                require(Arrays.equals(new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd},
                        Arrays.copyOf(compressed, 4)), "Native output is not a Zstd frame");
            }
            byte[] envelope = (byte[]) encode.invoke(instance, meta, record);
            require(envelope.length > 80, "NCBL envelope has no compressed body");
            require(Arrays.equals(new byte[] {0x4e, 0x43, 0x42, 0x4c, 3, 0, 0, 0},
                    Arrays.copyOf(envelope, 8)), "NCBL v3 header differs");
            int headerSize = (envelope[8] & 0xff) | ((envelope[9] & 0xff) << 8);
            require(headerSize == 76, "NCBL metadata length differs");
            int bodySize = (envelope[66] & 0xff) | ((envelope[67] & 0xff) << 8)
                    | ((envelope[68] & 0xff) << 16) | ((envelope[69] & 0xff) << 24);
            require(headerSize + bodySize == envelope.length, "NCBL frame accounting differs");
            require(uuids.add(hex(Arrays.copyOfRange(envelope, 10, 26))), "NCBL UUID was reused");
        }
    }

    private String required(String name) {
        String value = arguments.getString(name);
        require(value != null && !value.isEmpty(), "Missing instrumentation argument: " + name);
        return value;
    }

    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536];
            int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 0xff) >>> 4, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
