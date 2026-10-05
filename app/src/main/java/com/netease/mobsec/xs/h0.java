package com.netease.mobsec.xs;



public interface h0 extends android.os.IInterface {


    public static abstract class a extends android.os.Binder implements com.netease.mobsec.xs.h0 {
        public static final  int a = 0;



        public static class C0009a implements com.netease.mobsec.xs.h0 {
            public android.os.IBinder a;

            public C0009a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // android.os.IInterface
            public android.os.IBinder asBinder() {
                return this.a;
            }

            @Override // com.netease.mobsec.xs.h0
            public boolean d() throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.uodis.opendevice.aidl.OpenDeviceIdentifierService");
                    if (!this.a.transact(2, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.h0.a.a;
                    }
                    parcelObtain2.readException();
                    return parcelObtain2.readInt() != 0;
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.netease.mobsec.xs.h0
            public java.lang.String e() throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.uodis.opendevice.aidl.OpenDeviceIdentifierService");
                    if (!this.a.transact(1, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.h0.a.a;
                    }
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }
    }

    boolean d() throws android.os.RemoteException;

    java.lang.String e() throws android.os.RemoteException;
}
