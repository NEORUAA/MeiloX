package com.netease.mobsec.poly;



public abstract class c extends android.os.Binder implements com.netease.mobsec.poly.d {
    static final java.lang.String a = "com.android.internal.telephony.IPhoneSubInfo";
    public static final int b = 1;
    public static final int c = 2;
    public static final int d = 3;
    public static final int e = 4;
    public static final int f = 7;
    public static final int g = 9;
    public static final int h = 11;
    public static final int i = 13;

    public c() {
        attachInterface(this, a);
    }

    public static com.netease.mobsec.poly.d a(android.os.IBinder iBinder) {
        if (iBinder == null) {
            return null;
        }
        android.os.IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(a);
        return (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.poly.d)) ? new com.netease.mobsec.poly.b(iBinder) : (com.netease.mobsec.poly.d) iInterfaceQueryLocalInterface;
    }

    @Override // android.os.IInterface
    public android.os.IBinder asBinder() {
        return this;
    }

    @Override // android.os.Binder
    public boolean onTransact(int i2, android.os.Parcel parcel, android.os.Parcel parcel2, int i3) throws android.os.RemoteException {
        java.lang.String strC;
        if (i2 == 7) {
            parcel.enforceInterface(a);
            strC = c();
        } else if (i2 == 9) {
            parcel.enforceInterface(a);
            strC = d();
        } else if (i2 == 11) {
            parcel.enforceInterface(a);
            strC = a();
        } else if (i2 == 13) {
            parcel.enforceInterface(a);
            strC = e();
        } else {
            if (i2 == 1598968902) {
                parcel2.writeString(a);
                return true;
            }
            if (i2 == 1) {
                parcel.enforceInterface(a);
                strC = b();
            } else if (i2 == 2) {
                parcel.enforceInterface(a);
                strC = a(parcel.readInt());
            } else if (i2 == 3) {
                parcel.enforceInterface(a);
                strC = b(parcel.readInt());
            } else {
                if (i2 != 4) {
                    return super.onTransact(i2, parcel, parcel2, i3);
                }
                parcel.enforceInterface(a);
                strC = c(parcel.readInt());
            }
        }
        parcel2.writeNoException();
        parcel2.writeString(strC);
        return true;
    }
}
