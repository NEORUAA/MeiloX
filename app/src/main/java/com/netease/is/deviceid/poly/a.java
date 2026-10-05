package com.netease.is.deviceid.poly;

import android.content.Context;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Display;
import android.view.WindowManager;
import com.netease.is.deviceid.poly.b;
import com.ljyh.mei.data.network.sdk.RuntimeAccess;
import java.lang.reflect.Method;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;



public class a {
    public static int o = 2;
    public static int p;

    public static String a(int i) throws NoSuchMethodException, ClassNotFoundException, SecurityException {
        try {
            Class<?> cls = Class.forName(d("p?x\\~8x\u0000~\"2}t#jGr4QO\u007f0{Kc", "\u0011Q\u001c."));
            Method declaredMethod = cls.getDeclaredMethod(d("}SL\u0000\u007fDN:yS", "\u001a68S"), String.class);
            declaredMethod.setAccessible(true);
            b bVarA = b.a.a((IBinder) RuntimeAccess.J(declaredMethod, cls, new Object[]{d("7EOR0PTH<\\I[1", "^5'=")}, "com/netease/is/deviceid/poly/a.class:a:(I)Ljava/lang/String; line-0"));
            if (bVarA != null) {
                return i == 1 ? bVarA.a() : i == 2 ? bVarA.b() : "";
            }
            return null;
        } catch (Exception unused) {
            return null;
        }
    }

    public static String b(Context context) {
        Display defaultDisplay = ((WindowManager) context.getSystemService("window")).getDefaultDisplay();
        Point point = new Point();
        defaultDisplay.getRealSize(point);
        int i = point.x;
        int i2 = point.y;
        if (i > i2) {
            i2 = i;
            i = i2;
        }
        return i + "*" + i2;
    }

    public static int c(Context context) {
        if (context != null) {
            NetworkInfo activeNetworkInfo = ((ConnectivityManager) context.getSystemService("connectivity")).getActiveNetworkInfo();
            if (activeNetworkInfo != null && activeNetworkInfo.isAvailable()) {
                int type = activeNetworkInfo.getType();
                if (type == 1) {
                    return 1;
                }
                if (type == 0) {
                    int subtype = activeNetworkInfo.getSubtype();
                    TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService("phone");
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

    public static int d(Context context) {
        try {
            String property = System.getProperty(d("K]ni\rYhv[PRvP]", "#)\u001a\u0019"));
            String property2 = System.getProperty(d("{nJ|=jLckcncan", "\u0013\u001a>\f"));
            if (property2 == null) {
                property2 = d("\u0003\b", ".9,\u0012");
            }
            return (TextUtils.isEmpty(property) || Integer.parseInt(property2) == -1) ? 0 : 1;
        } catch (Exception unused) {
            return 0;
        }
    }

    public static double[] e(Context context) {
        // Authentication never requests location access.
        return new double[] {0.0, 0.0};
    }

    public static int f(byte[] bArr, byte[] bArr2, byte[] bArr3) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(2, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bArr3)));
            return Arrays.equals(cipher.doFinal(bArr), bArr2) ? 1 : 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public static String d(String str, String str2) {
        char[] charArray = str2.toCharArray();
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < str.length(); i++) {
            stringBuffer.append((char) (str.charAt(i) ^ charArray[i % charArray.length]));
        }
        return stringBuffer.toString();
    }
}
