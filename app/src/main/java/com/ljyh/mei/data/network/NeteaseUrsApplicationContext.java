package com.ljyh.mei.data.network;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.test.mock.MockPackageManager;

/**
 * Context used only by the isolated URS runtime so its official app signature remains coherent.
 */
final class NeteaseUrsApplicationContext extends ContextWrapper {
    private final PackageManager packageManager;

    NeteaseUrsApplicationContext(Context base, byte[] officialCertificate) {
        super(base);
        packageManager = new OfficialSignaturePackageManager(
                base.getPackageManager(),
                base.getPackageName(),
                officialCertificate
        );
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public PackageManager getPackageManager() {
        return packageManager;
    }

    private static final class OfficialSignaturePackageManager extends MockPackageManager {
        private final PackageManager delegate;
        private final String targetPackage;
        private final Signature officialSignature;

        OfficialSignaturePackageManager(
                PackageManager delegate,
                String targetPackage,
                byte[] officialCertificate
        ) {
            this.delegate = delegate;
            this.targetPackage = targetPackage;
            this.officialSignature = new Signature(officialCertificate);
        }

        @Override
        @SuppressWarnings("deprecation")
        public PackageInfo getPackageInfo(String packageName, int flags)
                throws NameNotFoundException {
            PackageInfo packageInfo = delegate.getPackageInfo(packageName, flags);
            if (targetPackage.equals(packageName)) {
                packageInfo.signatures = new Signature[]{officialSignature};
            }
            return packageInfo;
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags)
                throws NameNotFoundException {
            return delegate.getApplicationInfo(packageName, flags);
        }

        @Override
        public ProviderInfo resolveContentProvider(String authority, int flags) {
            return delegate.resolveContentProvider(authority, flags);
        }

        @Override
        public int checkPermission(String permissionName, String packageName) {
            return delegate.checkPermission(permissionName, packageName);
        }

        @Override
        public boolean hasSystemFeature(String featureName) {
            return delegate.hasSystemFeature(featureName);
        }

        @Override
        public boolean hasSystemFeature(String featureName, int version) {
            return delegate.hasSystemFeature(featureName, version);
        }
    }
}
