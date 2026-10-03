package com.netease.mobsec;



public class WatchManResult {
    int a;
    java.lang.String b;
    java.lang.String c;

    WatchManResult(int i, java.lang.String str, java.lang.String str2) {
        this.a = i;
        this.b = str;
        this.c = str2;
    }

    public static com.netease.mobsec.WatchManResult error(int i, java.lang.String str) {
        return new com.netease.mobsec.WatchManResult(i, str, "");
    }

    public static com.netease.mobsec.WatchManResult info(int i, java.lang.String str, java.lang.String str2) {
        return new com.netease.mobsec.WatchManResult(i, str, str2);
    }

    public int getCode() {
        return this.a;
    }

    public java.lang.String getMsg() {
        return this.b;
    }

    public java.lang.String getToken() {
        return this.c;
    }
}
