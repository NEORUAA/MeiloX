package com.netease.mobsec.xs;



public interface a extends android.os.IInterface {



    public static abstract class AbstractBinderC0002a extends android.os.Binder implements com.netease.mobsec.xs.a {
        public static final  int a = 0;



        public static class C0003a implements com.netease.mobsec.xs.a {
            public android.os.IBinder a;

            public C0003a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // com.netease.mobsec.xs.a
            public java.lang.String a() throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.android.creator.IdsSupplier");
                    if (!this.a.transact(2, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.a.AbstractBinderC0002a.a;
                    }
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // android.os.IInterface
            public android.os.IBinder asBinder() {
                return this.a;
            }
        }
    }

    java.lang.String a() throws android.os.RemoteException;
}
