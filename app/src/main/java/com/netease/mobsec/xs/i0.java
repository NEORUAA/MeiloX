package com.netease.mobsec.xs;



public interface i0 extends android.os.IInterface {


    public static abstract class a extends android.os.Binder implements com.netease.mobsec.xs.i0 {
        public static final  int a = 0;



        public static class C0010a implements com.netease.mobsec.xs.i0 {
            public android.os.IBinder a;

            public C0010a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // android.os.IInterface
            public android.os.IBinder asBinder() {
                return this.a;
            }

            @Override // com.netease.mobsec.xs.i0
            public java.lang.String c() throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.zui.deviceidservice.IDeviceidInterface");
                    if (!this.a.transact(1, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.i0.a.a;
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

    java.lang.String c() throws android.os.RemoteException;
}
