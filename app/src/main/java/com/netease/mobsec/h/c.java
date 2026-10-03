package com.netease.mobsec.h;



public class c implements com.netease.mobsec.AbstractNetClient {
    java.lang.String a(java.io.InputStream inputStream) {
        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
        byte[] bArr = new byte[1024];
        while (true) {
            try {
                int i = inputStream.read(bArr);
                if (i == -1) {
                    java.lang.String str = new java.lang.String(byteArrayOutputStream.toByteArray());
                    try {
                        inputStream.close();
                        byteArrayOutputStream.close();
                        return str;
                    } catch (java.io.IOException unused) {
                        return str;
                    }
                }
                byteArrayOutputStream.write(bArr, 0, i);
            } catch (java.io.IOException unused2) {
                return "";
            }
        }
    }

    @Override // com.netease.mobsec.AbstractNetClient
    public android.util.Pair<java.lang.Integer, java.lang.String> sendGet(java.lang.String str, int i) {
        try {
            java.net.HttpURLConnection httpURLConnection = (java.net.HttpURLConnection) new java.net.URL(str).openConnection();
            httpURLConnection.setReadTimeout(i);
            httpURLConnection.setConnectTimeout(i);
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setRequestProperty("accept", "*/*");
            httpURLConnection.setRequestProperty("connection", "Keep-Alive");
            if (httpURLConnection.getResponseCode() == 200) {
                return new android.util.Pair<>(java.lang.Integer.valueOf(httpURLConnection.getResponseCode()), a(httpURLConnection.getInputStream()));
            }
        } catch (java.lang.Exception unused) {
        }
        return null;
    }

    @Override // com.netease.mobsec.AbstractNetClient
    public android.util.Pair<java.lang.Integer, java.lang.String> sendPost(java.lang.String str, java.lang.String str2, int i) {
        try {
            byte[] bytes = str2.getBytes();
            java.net.HttpURLConnection httpURLConnection = (java.net.HttpURLConnection) new java.net.URL(str).openConnection();
            httpURLConnection.setConnectTimeout(i);
            httpURLConnection.setReadTimeout(i);
            httpURLConnection.setRequestMethod("POST");
            httpURLConnection.setDoOutput(true);
            httpURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            httpURLConnection.setRequestProperty("Content-Length", java.lang.String.valueOf(bytes.length));
            httpURLConnection.getOutputStream().write(bytes);
            return new android.util.Pair<>(java.lang.Integer.valueOf(httpURLConnection.getResponseCode()), a(httpURLConnection.getInputStream()));
        } catch (java.lang.Exception unused) {
            return null;
        }
    }
}
