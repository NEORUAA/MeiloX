package com.netease.mobsec;



public interface AbstractNetClient {
    android.util.Pair<java.lang.Integer, java.lang.String> sendGet(java.lang.String str, int i);

    android.util.Pair<java.lang.Integer, java.lang.String> sendPost(java.lang.String str, java.lang.String str2, int i);
}
