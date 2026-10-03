package com.netease.mobsec.xs;



public interface e extends android.os.IInterface {


    public static abstract class a extends android.os.Binder implements com.netease.mobsec.xs.e {
        public static final  int a = 0;



        public static class C0007a implements com.netease.mobsec.xs.e {
            public android.os.IBinder a;

            public C0007a(android.os.IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // com.netease.mobsec.xs.e
            public java.lang.String a(java.lang.String str, java.lang.String str2, java.lang.String str3) throws android.os.RemoteException {
                android.os.Parcel parcelObtain = android.os.Parcel.obtain();
                android.os.Parcel parcelObtain2 = android.os.Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("com.heytap.openid.IOpenID");
                    parcelObtain.writeString(str);
                    parcelObtain.writeString(str2);
                    parcelObtain.writeString(str3);
                    if (!this.a.transact(1, parcelObtain, parcelObtain2, 0)) {
                        int i = com.netease.mobsec.xs.e.a.a;
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

    java.lang.String a(java.lang.String str, java.lang.String str2, java.lang.String str3) throws android.os.RemoteException;
}
