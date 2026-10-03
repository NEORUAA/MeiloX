package com.netease.mobsec.f.f;



public class b implements com.netease.mobsec.f.f.a {
    public java.lang.String a(java.lang.String str, java.lang.String str2) {
        char[] charArray = str2.toCharArray();
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            sb.append((char) (str.charAt(i) ^ charArray[i % charArray.length]));
        }
        return sb.toString();
    }

    @Override // com.netease.mobsec.f.f.a
    public final int b(android.content.Context context) {
        if (context == null) {
            return 0;
        }
        a(context);
        return 1;
    }

    @Override // com.netease.mobsec.f.f.a
    public final void a(android.content.Context context) {
        try {
            com.netease.mobsec.xs.internal.NativeLibraryLoader.load(context, "netmobsec-4.4.7");
        } catch (java.lang.Exception unused) {
        }
    }
}
