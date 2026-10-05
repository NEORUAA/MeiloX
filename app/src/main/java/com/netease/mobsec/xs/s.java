package com.netease.mobsec.xs;



public class s {
    public final int a;
    public final java.lang.String b;
    public final android.content.Context c;



    public s(android.content.Context context, java.lang.String str, int i) {
        this.c = context;
        this.b = str;
        i = Math.max(500, Math.min(10000, i));
        this.a = i;
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 187) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 69) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public com.netease.mobsec.xs.network.Result a(java.lang.String str, java.lang.String str2, java.lang.String str3, java.lang.String str4, boolean z, boolean z2, boolean z3, boolean z4, boolean z5) {
        com.netease.mobsec.xs.network.Result result;
        android.content.Context context = this.c;
        java.lang.String strWwy66f7bc987 = null;
        byte[] bArrAec4f0df9b91 = (context == null || android.text.TextUtils.isEmpty(str3)) ? null : com.netease.mobsec.xs.poly.a.aec4f0df9b91(context, str, str2, str3, str4, z, z2, 0, z3, z4, z5);
        if (bArrAec4f0df9b91 == null || bArrAec4f0df9b91.length <= 0) {
            return com.netease.mobsec.xs.network.Result.a(1000);
        }
        try {
            if (!com.netease.mobsec.xs.f.a(this.b)) {
                return com.netease.mobsec.xs.network.Result.a(1003);
            }
            java.lang.String strA = a(this.b, this.a, bArrAec4f0df9b91);
            if (android.text.TextUtils.isEmpty(strA)) {
                return new com.netease.mobsec.xs.network.Result(201, android.util.Base64.encodeToString(bArrAec4f0df9b91, 10));
            }
            org.json.JSONObject jSONObject = new org.json.JSONObject(strA);
            int i = jSONObject.getInt(a(new char[]{'s', 208, 213, 'R'}, "˝˦ʕͣɩʫ"));
            java.lang.String str5 = "";
            if (i != 200) {
                result = new com.netease.mobsec.xs.network.Result(i, "");
            } else {
                java.lang.String string = jSONObject.getJSONObject(a(new char[]{201, 179, '6', 139}, "̵ʡͱʾʼʁ")).toString();
                android.content.Context context2 = this.c;
                if (context2 != null && !string.isEmpty()) {
                    strWwy66f7bc987 = com.netease.mobsec.xs.poly.a.wwy66f7bc987(context2, string);
                }
                if (android.text.TextUtils.isEmpty(strWwy66f7bc987)) {
                    i = 1005;
                } else {
                    str5 = strWwy66f7bc987;
                }
                result = new com.netease.mobsec.xs.network.Result(i, str5);
            }
            return result;
        } catch (org.json.JSONException unused) {
            return com.netease.mobsec.xs.network.Result.a(1002);
        } catch (java.lang.Exception unused2) {
            return new com.netease.mobsec.xs.network.Result(201, android.util.Base64.encodeToString(bArrAec4f0df9b91, 10));
        }
    }



    public final java.lang.String a(java.lang.String str, int i, byte[] bArr) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection)new java.net.URL(str).openConnection(java.net.Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setDoOutput(true); connection.setConnectTimeout(i); connection.setReadTimeout(i);
            connection.setFixedLengthStreamingMode(bArr.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bArr); }
            if (connection.getResponseCode() != 200) return null;
            try (java.io.InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception error) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
