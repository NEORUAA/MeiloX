package com.netease.mobsec.xs;



public final class i implements android.os.IInterface {
    public final android.os.IBinder a;

    public i(android.os.IBinder iBinder) {
        this.a = iBinder;
    }

    public boolean a(boolean z) throws android.os.RemoteException {
        android.os.Parcel request = android.os.Parcel.obtain();
        android.os.Parcel response = android.os.Parcel.obtain();
        try {
            request.writeInterfaceToken("com.google.android.gms.ads.identifier.internal.IAdvertisingIdService");
            request.writeInt(z ? 1 : 0);
            a.transact(2, request, response, 0); response.readException();
            return response.readInt() != 0;
        } finally { response.recycle(); request.recycle(); }
    }

    @Override // android.os.IInterface
    public android.os.IBinder asBinder() {
        return this.a;
    }

    public java.lang.String f() throws android.os.RemoteException {
        android.os.Parcel request = android.os.Parcel.obtain();
        android.os.Parcel response = android.os.Parcel.obtain();
        try {
            request.writeInterfaceToken("com.google.android.gms.ads.identifier.internal.IAdvertisingIdService");
            a.transact(1, request, response, 0); response.readException();
            return response.readString();
        } finally { response.recycle(); request.recycle(); }
    }
}
