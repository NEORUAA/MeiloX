package com.netease.mobsec.xs.poly;



public class a {
    public static native byte[] aec4f0df9b91(android.content.Context context, java.lang.String str, java.lang.String str2, java.lang.String str3, java.lang.String str4, boolean z, boolean z2, int i, boolean z3, boolean z4, boolean z5);

    public static native boolean e6615a3e4d79(android.content.Context context);

    public static java.lang.String main2(int i, android.content.Context context) throws java.lang.IllegalAccessException, java.lang.NoSuchFieldException, java.lang.NoSuchMethodException, java.lang.SecurityException, java.lang.ClassNotFoundException, java.lang.IllegalArgumentException {
        android.util.ArrayMap arrayMap;
        java.util.List<android.content.ComponentName> activeAdmins;
        int i2;
        java.lang.StringBuilder sb;
        try {
            if (i == 1) {
                java.lang.reflect.Field declaredField = java.lang.Class.forName(com.netease.mobsec.xs.f.i(new char[]{'/', 153, 160, 233, 'k', 'g', 208, 161, 1, '(', ';', '^', 19, 200, 'f', '9', 156, '7', 198, 235, 'b', 216, 5, 166, 238, 184, 'x', 'F', 138, 236, 209, 'j', 0, 169, '4', 170, 'Q', '(', 128, 219, '<', '8', 144, 'X', 'A', 'Y', '*'}, "̯˯ʭͶ˟˙")).getDeclaredField(com.netease.mobsec.xs.f.i(new char[]{'W', 24, 1, 'T', '@', 'B', 'n', 171}, "˽ˠʪ˂ʴɥ"));
                declaredField.setAccessible(true);
                java.lang.Class<?> cls = java.lang.Class.forName(com.netease.mobsec.xs.f.i(new char[]{219, 244, 245, 'G', 160, 'Y', '*', 212, '}', '\r', 22, '0', 183, 'u', 203, 215, '?', ')', 245, 206, 183, 247, 167, 152, 187, 213}, "˒̂ˀɝʦͩ"));
                java.lang.reflect.Method declaredMethod = cls.getDeclaredMethod(com.netease.mobsec.xs.f.i(new char[]{15, 218, 255, 212, 137, '|', 176, '_', 233, 252, 248, 135, '>', 138, 25, 232, 184, 6, 198, 'S', 185}, "̾ʮɧ˙ˤ͇"), new java.lang.Class[0]);
                declaredMethod.setAccessible(true);
                java.lang.Object objJ = com.ljyh.mei.data.network.sdk.RuntimeAccess.J(declaredMethod, null, new java.lang.Object[0], "com/netease/mobsec/xs/poly/a.class:main2:(ILandroid/content/Context;)Ljava/lang/String; line-5");
                java.lang.reflect.Field declaredField2 = cls.getDeclaredField(com.netease.mobsec.xs.f.i(new char[]{'N', 'u', 'C', 175, 165, 'x', 183, 243, 4, 181, 226}, "̡̺ʍ̆ʈͿ"));
                declaredField2.setAccessible(true);
                java.lang.Object obj = declaredField2.get(objJ);
                if ((obj instanceof android.util.ArrayMap) && (arrayMap = (android.util.ArrayMap) obj) != null && arrayMap.size() > 0) {
                    android.util.ArraySet arraySet = new android.util.ArraySet();
                    int i3 = 0;
                    for (java.lang.Object obj2 : arrayMap.values().toArray()) {
                        if (i3 <= 50 && arraySet.size() <= 10) {
                            if (obj2 != null) {
                                java.lang.String str = (java.lang.String) declaredField.get(obj2);
                                if (!android.text.TextUtils.isEmpty(str)) {
                                    arraySet.add(str);
                                }
                                i3++;
                            }
                        }
                    }
                    return android.text.TextUtils.join(com.netease.mobsec.xs.f.i(new char[]{197}, "ˎ̢̯̟͚\u0380"), arraySet);
                }
            } else if (i != 2) {
                if (i == 3 && context != null) {
                    try {
                        android.view.Display[] displays = ((android.hardware.display.DisplayManager) context.getApplicationContext().getSystemService(com.netease.mobsec.xs.f.i(new char[]{'}', 194, 150, 16, '>', '\\', 11}, "˄Ͳʪ̵̻͉"))).getDisplays();
                        if (displays.length > 1) {
                            for (android.view.Display display : displays) {
                                int displayId = display.getDisplayId();
                                if (displayId != 0 && displayId != 4096 && display.getState() == 2 && (display.getFlags() & 8) != 0) {
                                    sb = new java.lang.StringBuilder();
                                    sb.append(display.getName());
                                    sb.append(com.netease.mobsec.xs.f.i(new char[]{'\"'}, "˘ʟ̍ʿ̫͠"));
                                    sb.append(com.netease.mobsec.xs.f.a(display));
                                    sb.append(com.netease.mobsec.xs.f.i(new char[]{'$'}, "ʺ˔ˣ̭ͽͥ"));
                                    sb.append(displayId);
                                    break;
                                }
                            }
                        }
                        synchronized (com.netease.mobsec.xs.b0.class) {
                            i2 = com.netease.mobsec.xs.b0.b;
                            com.netease.mobsec.xs.b0.b = 0;
                        }
                        if (i2 > 0) {
                            java.lang.StringBuilder sb2 = new java.lang.StringBuilder();
                            sb2.append(com.netease.mobsec.xs.f.i(new char[]{26, 217, '(', 'J', 158, 153, '+', 129, 136, 'S', 16, '\b', 204, 200, 223, 4}, "ˀ˭ͭ˹̵͵"));
                            sb2.append(i2);
                            sb = sb2;
                            return sb.toString();
                        }
                    } catch (java.lang.Throwable unused) {
                    }
                    return com.netease.mobsec.xs.f.i(new char[]{'M', 139, 255, 26, 188, 176, ':', 179, 208, 'B', 'k', 'B', 28, 187, 'Y', 152}, "͖̿͡ͳ͙̤");
                }
            } else if (context != null && (activeAdmins = ((android.app.admin.DevicePolicyManager) context.getSystemService(com.netease.mobsec.xs.f.i(new char[]{'J', 162, 'I', 169, 172, 230, 216, ';', '(', 'H', 'U', 150, 156}, "͓Ϳɩɫˉ˙"))).getActiveAdmins()) != null && activeAdmins.size() > 0) {
                java.util.ArrayList arrayList = new java.util.ArrayList();
                int i4 = 0;
                for (android.content.ComponentName componentName : activeAdmins) {
                    if (i4 > 10) {
                        break;
                    }
                    if (componentName != null) {
                        arrayList.add(componentName.getPackageName());
                        i4++;
                    }
                }
                return android.text.TextUtils.join(com.netease.mobsec.xs.f.i(new char[]{'2'}, "ɚ̦ͬɤʆˁ"), arrayList);
            }
        } catch (java.lang.Exception unused2) {
        }
        return "";
    }

    public static native java.lang.String wwy66f7bc987(android.content.Context context, java.lang.String str);
}
