package com.netease.mobsec;



public class WatchManConf {
    java.lang.String a = "";
    java.lang.String b = "";
    java.lang.String c = "";
    com.netease.mobsec.AbstractNetClient d = null;
    final java.util.Map<java.lang.String, java.lang.String> e = new java.util.HashMap();
    boolean f = true;
    boolean g = true;
    boolean h = false;
    boolean i = false;

    public com.netease.mobsec.AbstractNetClient getAbstractNetClient() {
        return this.d;
    }

    public java.lang.String getChannel() {
        return this.b;
    }

    public boolean getCollectApk() {
        return this.f;
    }

    public boolean getCollectSensor() {
        return this.g;
    }

    public java.lang.String getCustomTrackId() {
        return this.c;
    }

    public java.util.Map<java.lang.String, java.lang.String> getExtraData() {
        return this.e;
    }

    public boolean getInitDInfo() {
        return this.i;
    }

    public java.lang.String getUrl() {
        return this.a;
    }

    public boolean isOnCoroutines() {
        return this.h;
    }

    public void setAbstractNetClient(com.netease.mobsec.AbstractNetClient abstractNetClient) {
        this.d = abstractNetClient;
    }

    public void setChannel(java.lang.String str) {
        this.b = str;
    }

    public void setCollectApk(boolean z) {
        this.f = z;
    }

    public void setCollectSensor(boolean z) {
        this.g = z;
    }

    public void setCustomTrackId(java.lang.String str) {
        this.c = str;
    }

    public void setExtraData(java.lang.String str, java.lang.String str2) {
        this.e.put(str, str2);
    }

    public void setInitDInfo(boolean z) {
        this.i = z;
    }

    public void setOnCoroutines(boolean z) {
        this.h = z;
    }

    public void setUrl(java.lang.String str) {
        this.a = str;
    }
}
