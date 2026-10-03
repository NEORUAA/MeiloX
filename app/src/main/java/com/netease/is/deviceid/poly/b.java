package com.netease.is.deviceid.poly;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;



public interface b extends IInterface {
    String a() throws RemoteException;

    String a(int i) throws RemoteException;

    String b() throws RemoteException;

    String b(int i) throws RemoteException;

    String c() throws RemoteException;

    String c(int i) throws RemoteException;

    String d() throws RemoteException;

    String e() throws RemoteException;


    public static abstract class a extends Binder implements b {
        public static final int a = 1;
        public static final int b = 2;
        public static final int c = 3;
        public static final int d = 4;
        public static final int e = 7;
        public static final int f = 9;
        public static final int g = 11;
        public static final int h = 13;
        private static String i = "'rC\u000b%sJW+tJ\u000b-sZ@6sOIjiKI!mFJ*d\u0000l\u0014uAK!N[G\rsHJ";
        private static String j = com.netease.is.deviceid.poly.a.d("'rC\u000b%sJW+tJ\u000b-sZ@6sOIjiKI!mFJ*d\u0000l\u0014uAK!N[G\rsHJ", "D\u001d.%");



        public static class C0082a implements b {
            private IBinder a;

            C0082a(IBinder iBinder) {
                this.a = iBinder;
            }

            @Override // com.netease.is.deviceid.poly.b
            public String a() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    this.a.transact(1, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.a;
            }

            @Override // com.netease.is.deviceid.poly.b
            public String b() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    this.a.transact(7, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.netease.is.deviceid.poly.b
            public String c() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    this.a.transact(9, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.netease.is.deviceid.poly.b
            public String d() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    this.a.transact(11, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.netease.is.deviceid.poly.b
            public String e() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    this.a.transact(13, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            public String f() {
                return com.netease.is.deviceid.poly.b.a.j;
            }

            @Override // com.netease.is.deviceid.poly.b
            public String a(int i) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    parcelObtain.writeInt(i);
                    this.a.transact(2, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.netease.is.deviceid.poly.b
            public String b(int i) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    parcelObtain.writeInt(i);
                    this.a.transact(3, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.netease.is.deviceid.poly.b
            public String c(int i) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(com.netease.is.deviceid.poly.b.a.j);
                    parcelObtain.writeInt(i);
                    this.a.transact(4, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }

        public a() {
            attachInterface(this, j);
        }

        public static b a(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(j);
            return (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof b)) ? new C0082a(iBinder) : (b) iInterfaceQueryLocalInterface;
        }

        @Override // android.os.Binder
        public boolean onTransact(int i2, Parcel parcel, Parcel parcel2, int i3) throws RemoteException {
            String strA;
            if (i2 == 1) {
                parcel.enforceInterface(j);
                strA = a();
            } else if (i2 == 2) {
                parcel.enforceInterface(j);
                strA = a(parcel.readInt());
            } else if (i2 == 3) {
                parcel.enforceInterface(j);
                strA = b(parcel.readInt());
            } else if (i2 == 4) {
                parcel.enforceInterface(j);
                strA = c(parcel.readInt());
            } else if (i2 == 7) {
                parcel.enforceInterface(j);
                strA = b();
            } else if (i2 == 9) {
                parcel.enforceInterface(j);
                strA = c();
            } else if (i2 == 11) {
                parcel.enforceInterface(j);
                strA = d();
            } else {
                if (i2 != 13) {
                    if (i2 != 1598968902) {
                        return super.onTransact(i2, parcel, parcel2, i3);
                    }
                    strA = j;
                    parcel2.writeString(strA);
                    return true;
                }
                parcel.enforceInterface(j);
                strA = e();
            }
            parcel2.writeNoException();
            parcel2.writeString(strA);
            return true;
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }
    }
}
