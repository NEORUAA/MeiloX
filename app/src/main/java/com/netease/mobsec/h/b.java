package com.netease.mobsec.h;



public class b {
    long a;
    long b;
    long c;
    android.content.Context d;
    long e = 0;
    int f = 0;
    long g = 0;

    public b(android.content.Context context) {
        this.d = context;
    }

    public int a(org.json.JSONObject jSONObject) {
        int i = -1;
        int i2 = -1;
        if (jSONObject == null) {
            return -1;
        }
        try {
            int iOptInt = jSONObject.optInt("code", -1);
            try {
                org.json.JSONObject jSONObject2 = new org.json.JSONObject(com.netease.mobsec.h.q.a(jSONObject.optString("result")));
                java.lang.String strOptString = jSONObject2.optString("dcu");
                if (strOptString.startsWith("ac")) {
                    strOptString = java.lang.String.format("%s%s", "https://", strOptString);
                }
                java.lang.String str = strOptString;
                java.lang.String strOptString2 = jSONObject2.optString("bcu");
                if (strOptString2.startsWith("ac")) {
                    strOptString2 = java.lang.String.format("%s%s", "https://", strOptString2);
                }
                java.lang.String str2 = strOptString2;
                int iOptInt2 = jSONObject2.optInt("ivp");
                int iOptInt3 = jSONObject2.optInt("dtvp");
                int i3 = iOptInt3 < 90 ? 90 : iOptInt3;
                int iOptInt4 = jSONObject2.optInt("tto");
                boolean zOptBoolean = jSONObject2.optBoolean("edc");
                java.lang.String strOptString3 = jSONObject2.optString("bl");
                this.a = f() + (iOptInt2 / 1000);
                this.e = jSONObject2.optLong("sim");
                this.f = jSONObject2.optInt("mun");
                this.g = jSONObject2.optLong("mmm");
                i = iOptInt;
                try {
                    a(str, str2, iOptInt2, i3, iOptInt4, zOptBoolean, strOptString3, jSONObject2.optBoolean("eau", false), jSONObject2.optLong("adt", 50L), jSONObject2.optBoolean("etc", false), jSONObject2.optLong("tct", 0L));
                    return i;
                } catch (java.lang.Exception unused) {
                    i2 = i;
                    return i2;
                }
            } catch (java.lang.Exception unused2) {
                i = iOptInt;
            }
        } catch (java.lang.Exception unused3) {
        }
        return i;
    }

    public int b(org.json.JSONObject jSONObject) {
        java.lang.String strOptString;
        java.lang.String strOptString2;
        java.lang.String strOptString3;
        java.lang.String strOptString4;
        java.lang.String strOptString5;
        if (jSONObject == null) {
            return -1;
        }
        int iOptInt = jSONObject.optInt("code", -1);
        org.json.JSONObject jSONObjectOptJSONObject = jSONObject.optJSONObject("result");
        if (jSONObjectOptJSONObject != null) {
            strOptString2 = jSONObjectOptJSONObject.optString("tid");
            strOptString3 = jSONObjectOptJSONObject.optString("timestamp");
            strOptString4 = jSONObjectOptJSONObject.has("dt") ? jSONObjectOptJSONObject.optString("dt") : "";
            strOptString5 = jSONObjectOptJSONObject.has("ni") ? jSONObjectOptJSONObject.optString("ni") : "";
            strOptString = jSONObjectOptJSONObject.has("di") ? jSONObjectOptJSONObject.optString("di") : "";
        } else {
            strOptString = "";
            strOptString2 = strOptString;
            strOptString3 = strOptString2;
            strOptString4 = strOptString3;
            strOptString5 = strOptString4;
        }
        if (iOptInt == 200) {
            if (!strOptString2.equals("")) {
                d(strOptString2);
            }
            if (!strOptString4.equals("")) {
                a(strOptString4);
            }
            if (!strOptString5.equals("")) {
                c(strOptString5);
            }
            if (!strOptString.equals("")) {
                b(strOptString);
            }
        } else if (iOptInt == 420 && !strOptString3.equals("")) {
            long j = java.lang.Long.parseLong(strOptString3) / 1000;
            if (j != 0) {
                c(this.b - j);
            }
        }
        return iOptInt;
    }

    public java.lang.String c() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getString("I", "");
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    public void d() {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.remove("DU");
            editorEdit.remove("BU");
            editorEdit.remove("IVP");
            editorEdit.remove("IVPT");
            editorEdit.remove("DTVP");
            editorEdit.remove("TTO");
            editorEdit.remove("EDC");
            editorEdit.remove("bl");
            editorEdit.commit();
        } catch (java.lang.Exception unused) {
        }
    }

    public long e() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("adt", 50L);
        } catch (java.lang.Exception unused) {
            return 50L;
        }
    }

    public long f() {
        long jCurrentTimeMillis = java.lang.System.currentTimeMillis() / 1000;
        this.b = jCurrentTimeMillis;
        return jCurrentTimeMillis;
    }

    public long g() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("DTVPT", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public long h() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("DST", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public boolean i() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getBoolean("eau", true);
        } catch (java.lang.Exception unused) {
            return true;
        }
    }

    public boolean j() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getBoolean("etc", false);
        } catch (java.lang.Exception unused) {
            return false;
        }
    }

    public java.lang.String k() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getString("BU", "");
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    public boolean l() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getBoolean("EDC", true);
        } catch (java.lang.Exception unused) {
            return true;
        }
    }

    public java.lang.String m() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getString("DU", "");
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    public long n() {
        long j;
        try {
            j = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("DTVP", 0L);
        } catch (java.lang.Exception unused) {
            j = 0;
        }
        if (j <= 0) {
            return 3600000L;
        }
        return j;
    }

    public long o() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("IVPT", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public long p() {
        long jCurrentTimeMillis = java.lang.System.currentTimeMillis() / 1000;
        this.b = jCurrentTimeMillis;
        return jCurrentTimeMillis - this.c;
    }

    public long q() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("tct", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public long r() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("TTO", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public java.lang.String s() {
        return this.d == null ? "" : t();
    }

    public java.lang.String t() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getString("A", "");
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    public long u() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("mmm", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public int v() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getInt("mun", 0);
        } catch (java.lang.Exception unused) {
            return 0;
        }
    }

    public long w() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getLong("sim", 0L);
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    void c(long j) {
        this.c = j;
    }

    void e(java.lang.String str) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putString("A", str);
            editorEdit.commit();
        } catch (java.lang.Exception unused) {
        }
    }

    public long a(long j) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putLong("DTVPT", j);
            editorEdit.commit();
            return 0L;
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public long b(long j) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putLong("DST", j);
            editorEdit.commit();
            return 0L;
        } catch (java.lang.Exception unused) {
            return 0L;
        }
    }

    public void d(java.lang.String str) {
        e(str);
    }

    void c(java.lang.String str) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putString("I", str);
            editorEdit.commit();
        } catch (java.lang.Exception unused) {
        }
    }

    public java.lang.String a() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getString("D", "");
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    public java.lang.String b() {
        try {
            return this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).getString("DI", "");
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    void b(java.lang.String str) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putString("DI", str);
            editorEdit.commit();
        } catch (java.lang.Exception unused) {
        }
    }

    public java.lang.String a(java.lang.String str, java.lang.String str2) {
        char[] charArray = str2.toCharArray();
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            sb.append((char) (str.charAt(i) ^ charArray[i % charArray.length]));
        }
        return sb.toString();
    }

    void a(java.lang.String str) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putString("D", str);
            editorEdit.commit();
        } catch (java.lang.Exception unused) {
        }
    }

    public void a(java.lang.String str, java.lang.String str2, int i, int i2, int i3, boolean z, java.lang.String str3, boolean z2, long j, boolean z3, long j2) {
        try {
            android.content.SharedPreferences.Editor editorEdit = this.d.getSharedPreferences(a("VZTmVMT\u007fh\\]mV_Pu|LnwlJn", "\t)1\u0019"), 0).edit();
            editorEdit.putString("DU", str);
            editorEdit.putString("BU", str2);
            editorEdit.putLong("IVP", i);
            editorEdit.putLong("IVPT", this.a);
            editorEdit.putLong("DTVP", i2);
            editorEdit.putLong("TTO", i3);
            editorEdit.putBoolean("EDC", z);
            editorEdit.putString("bl", str3);
            editorEdit.putLong("sim", this.e);
            editorEdit.putLong("mmm", this.g);
            editorEdit.putInt("mun", this.f);
            editorEdit.putBoolean("eau", z2);
            editorEdit.putLong("adt", j);
            editorEdit.putBoolean("etc", z3);
            editorEdit.putLong("tct", j2);
            editorEdit.commit();
        } catch (java.lang.Exception unused) {
        }
    }
}
