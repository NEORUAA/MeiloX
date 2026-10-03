package com.netease.mobsec.xs.network;



public class Result {
    public final int a;
    public final java.lang.String b;

    public Result(int i, java.lang.String str) {
        this.a = i;
        this.b = str;
    }

    public static com.netease.mobsec.xs.network.Result a(int i) {
        return new com.netease.mobsec.xs.network.Result(i, "");
    }

    public int getCode() {
        return this.a;
    }

    public java.lang.String getToken() {
        return this.b;
    }
}
