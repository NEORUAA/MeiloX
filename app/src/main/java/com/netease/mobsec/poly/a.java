package com.netease.mobsec.poly;



public class a {
    public static int o = 2;
    public static int p;

    public static java.lang.String a(int i) throws java.lang.NoSuchMethodException, java.lang.ClassNotFoundException, java.lang.SecurityException {
        try {
            java.lang.Class<?> cls = java.lang.Class.forName(d("p?x\\~8x\u0000~\"2}t#jGr4QO\u007f0{Kc", "\u0011Q\u001c."));
            java.lang.reflect.Method declaredMethod = cls.getDeclaredMethod(d("}SL\u0000\u007fDN:yS", "\u001a68S"), java.lang.String.class);
            declaredMethod.setAccessible(true);
            com.netease.mobsec.poly.d dVarA = com.netease.mobsec.poly.c.a((android.os.IBinder) com.ljyh.mei.data.network.sdk.RuntimeAccess.J(declaredMethod, cls, new java.lang.Object[]{d("7EOR0PTH<\\I[1", "^5'=")}, "com/netease/mobsec/poly/a.class:a:(I)Ljava/lang/String; line-0"));
            if (dVarA != null) {
                return i == 1 ? dVarA.b() : i == 2 ? dVarA.c() : "";
            }
            return null;
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static java.lang.String b(android.content.Context context) {
        android.view.Display defaultDisplay = ((android.view.WindowManager) context.getSystemService("window")).getDefaultDisplay();
        android.graphics.Point point = new android.graphics.Point();
        defaultDisplay.getRealSize(point);
        return point.x + "*" + point.y;
    }

    public static int c(android.content.Context context) {
        if (context != null) {
            android.net.NetworkInfo activeNetworkInfo = ((android.net.ConnectivityManager) context.getSystemService("connectivity")).getActiveNetworkInfo();
            if (activeNetworkInfo != null && activeNetworkInfo.isAvailable()) {
                int type = activeNetworkInfo.getType();
                if (type == 1) {
                    return 1;
                }
                if (type == 0) {
                    int subtype = activeNetworkInfo.getSubtype();
                    android.telephony.TelephonyManager telephonyManager = (android.telephony.TelephonyManager) context.getSystemService("phone");
                    if (subtype == 13 && !telephonyManager.isNetworkRoaming()) {
                        return 4;
                    }
                    int i = 3;
                    if (subtype != 3 && subtype != 8 && subtype != 5 && subtype != 15 && subtype != 14 && subtype != 12 && subtype != 10 && subtype != 9 && (subtype != 6 || telephonyManager.isNetworkRoaming())) {
                        i = 2;
                        if (subtype != 1 && subtype != 2 && subtype != 4 && subtype != 11 && subtype == 7) {
                            telephonyManager.isNetworkRoaming();
                        }
                    }
                    return i;
                }
            } else if (activeNetworkInfo == null) {
                return 0;
            }
        }
        return -1;
    }

    public static int d(android.content.Context context) {
        try {
            java.lang.String property = java.lang.System.getProperty(d("K]ni\rYhv[PRvP]", "#)\u001a\u0019"));
            java.lang.String property2 = java.lang.System.getProperty(d("{nJ|=jLckcncan", "\u0013\u001a>\f"));
            if (property2 == null) {
                property2 = d("\u0003\b", ".9,\u0012");
            }
            return (android.text.TextUtils.isEmpty(property) || java.lang.Integer.parseInt(property2) == -1) ? 0 : 1;
        } catch (java.lang.Exception unused) {
            return 0;
        }
    }

    public static double[] e(android.content.Context context) {
        return new double[]{0.0d, 0.0d};
    }

    public static int f(byte[] bArr, byte[] bArr2, byte[] bArr3) throws javax.crypto.NoSuchPaddingException, java.security.NoSuchAlgorithmException, java.security.InvalidKeyException {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(2, java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(bArr3)));
            return java.util.Arrays.equals(cipher.doFinal(bArr), bArr2) ? 1 : 0;
        } catch (java.lang.Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public static java.lang.String d(java.lang.String str, java.lang.String str2) {
        char[] charArray = str2.toCharArray();
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            sb.append((char) (str.charAt(i) ^ charArray[i % charArray.length]));
        }
        return sb.toString();
    }
}
