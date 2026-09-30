package com.ljyh.mei.data.repository;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Platform-only test provider in a separate APK process; never addresses user files. */
public final class CloudUploadFixtureProvider extends ContentProvider {
    private static final class Fixture {
        final File file;
        final String mode;
        final int prefix;
        final byte[] bytes;
        final AtomicInteger queries = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger types = new AtomicInteger();
        final AtomicBoolean canceled = new AtomicBoolean();
        final AtomicBoolean querySignal = new AtomicBoolean();
        final AtomicBoolean openSignal = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);
        Fixture(File file, String mode, int prefix, byte[] bytes) {
            this.file = file; this.mode = mode; this.prefix = prefix; this.bytes = bytes;
        }
    }
    private final ConcurrentHashMap<String, Fixture> fixtures = new ConcurrentHashMap<>();
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        String id = Objects.requireNonNull(arg);
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid fixture identity");
        try {
            if (method.equals("create")) {
                String mode = extras == null ? "" : extras.getString("mode", "");
                byte[] bytes = mode.equals("empty") ? new byte[0] : audio();
                int prefix = mode.equals("slice") ? 32 : 0;
                File file = new File(Objects.requireNonNull(getContext()).getCacheDir(), "cloud-provider-" + id + ".wav");
                if (!file.createNewFile()) throw new IllegalStateException("Duplicate fixture file");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[prefix]); out.write(bytes); }
                if (fixtures.putIfAbsent(id, new Fixture(file, mode, prefix, bytes)) != null) throw new IllegalStateException("Duplicate fixture");
                Bundle result = new Bundle();
                result.putLong("size", bytes.length); result.putString("md5", md5(bytes));
                return result;
            }
            Fixture f = Objects.requireNonNull(fixtures.get(id));
            switch (method) {
                case "status":
                    Bundle result = new Bundle();
                    result.putInt("queries", f.queries.get()); result.putInt("opens", f.opens.get()); result.putInt("types", f.types.get());
                    result.putBoolean("canceled", f.canceled.get()); result.putBoolean("query_signal", f.querySignal.get());
                    result.putBoolean("open_signal", f.openSignal.get());
                    byte[] original = new byte[f.prefix + f.bytes.length];
                    System.arraycopy(f.bytes, 0, original, f.prefix, f.bytes.length);
                    result.putBoolean("source_unchanged", Arrays.equals(Files.readAllBytes(f.file.toPath()), original));
                    return result;
                case "release": f.release.countDown(); return Bundle.EMPTY;
                case "mutate":
                    try (FileOutputStream out = new FileOutputStream(f.file)) { out.write(new byte[] {1, 2, 3}); }
                    return Bundle.EMPTY;
                case "delete":
                    fixtures.remove(id); f.release.countDown();
                    if (!f.file.delete()) throw new IllegalStateException("Unable to delete fixture");
                    return Bundle.EMPTY;
                default: throw new IllegalArgumentException("Unknown fixture command");
            }
        } catch (Exception error) { throw new IllegalStateException("Fixture operation failed", error); }
    }

    private Fixture fixture(Uri uri) {
        Fixture value = fixtures.get(uri.getLastPathSegment());
        if (value == null) throw new IllegalArgumentException("Unknown fixture");
        return value;
    }
    private void await(Fixture f, CancellationSignal signal) {
        if (signal != null) signal.setOnCancelListener(() -> { f.canceled.set(true); f.release.countDown(); });
        try {
            if (!f.release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture was not released");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        if (signal != null) signal.throwIfCanceled();
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return query(uri, projection, selection, selectionArgs, sortOrder, null);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal signal) {
        Fixture f = fixture(uri);
        f.queries.incrementAndGet(); f.querySignal.set(signal != null);
        if (f.mode.equals("deny_query")) throw new SecurityException("Synthetic provider denial");
        if (f.mode.equals("block_query")) await(f, signal);
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[] {f.mode.equals("no_name") ? null : "Fixture song.WAV"});
        return cursor;
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException { return openAssetFile(uri, mode, null); }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeType, Bundle options, CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri, "r", signal);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!mode.equals("r")) throw new IllegalArgumentException("Read-only fixture");
        Fixture f = fixture(uri);
        f.opens.incrementAndGet(); f.openSignal.set(signal != null);
        if (f.mode.equals("deny_open")) throw new SecurityException("Synthetic provider denial");
        if (f.mode.equals("null_open")) return null;
        if (f.mode.equals("block_open")) await(f, signal);
        if (f.mode.equals("ignore_cancel")) await(f, null);
        if (f.mode.equals("broken_pipe")) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try {
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).write(new byte[] {1, 2, 3});
                        pipe[1].closeWithError("Synthetic source failure");
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }, "Cloud-provider-fixture").start();
                return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
        return new AssetFileDescriptor(ParcelFileDescriptor.open(f.file, ParcelFileDescriptor.MODE_READ_ONLY), f.prefix, f.bytes.length);
    }
    @Override public String getType(Uri uri) {
        Fixture f = fixture(uri); f.types.incrementAndGet();
        if (f.mode.equals("block_type")) await(f, null);
        if (f.mode.equals("deny_type")) throw new SecurityException("Synthetic MIME denial");
        return f.mode.equals("no_name") ? null : "audio/wav";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Use scoped fixture cleanup"); }

    private static byte[] audio() {
        return ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(16036).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(16000).array();
    }
    private static String md5(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("MD5").digest(bytes)) result.append(String.format("%02x", value));
        return result.toString();
    }
}
