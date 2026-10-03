package com.netease.mobsec.xs;



public interface b extends android.os.IInterface {


    public static abstract class a extends android.os.Binder implements com.netease.mobsec.xs.b {
        public static final  int a = 0;



        public static class C0004a implements com.netease.mobsec.xs.b {
            public android.os.IBinder a;

            public C0004a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // android.os.IInterface
            public android.os.IBinder asBinder() {
                return this.a;
            }

            @Override // com.netease.mobsec.xs.b
            public java.lang.String b() throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.asus.msa.SupplementaryDID.IDidAidlInterface");
                    if (!this.a.transact(3, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.b.a.a;
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

    java.lang.String b() throws android.os.RemoteException;
}
