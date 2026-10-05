package com.netease.mobsec.xs;



public interface c extends android.os.IInterface {


    public static abstract class a extends android.os.Binder implements com.netease.mobsec.xs.c {
        public static final  int a = 0;



        public static class C0005a implements com.netease.mobsec.xs.c {
            public android.os.IBinder a;

            public C0005a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // com.netease.mobsec.xs.c
            public java.lang.String a() throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.bun.lib.MsaIdInterface");
                    if (!this.a.transact(2, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.c.a.a;
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
