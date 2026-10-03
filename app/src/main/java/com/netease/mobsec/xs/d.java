package com.netease.mobsec.xs;



public interface d extends android.os.IInterface {


    public static abstract class a extends android.os.Binder implements com.netease.mobsec.xs.d {
        public static final  int a = 0;



        public static class C0006a implements com.netease.mobsec.xs.d {
            public android.os.IBinder a;

            public C0006a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // com.netease.mobsec.xs.d
            public java.lang.String a(java.lang.String str) throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.coolpad.deviceidsupport.IDeviceIdManager");
                    parcelObtain.writeString(str);
                    if (!this.a.transact(4, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.d.a.a;
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

    java.lang.String a(java.lang.String str) throws android.os.RemoteException;
}
