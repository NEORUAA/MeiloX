package com.netease.mobsec.xs;



public class NEDevice {
    public static final android.os.Handler a = new android.os.Handler(android.os.Looper.getMainLooper());
    public static final java.util.concurrent.ExecutorService b = java.util.concurrent.Executors.newSingleThreadExecutor();
    public volatile boolean c = false;
    public com.netease.mobsec.xs.s d;


    public class a implements java.lang.Runnable {
        public final  java.lang.String a;
        public final  java.lang.String b;
        public final  com.netease.mobsec.xs.NECallback c;



        public class RunnableC0000a implements java.lang.Runnable {
            public final  com.netease.mobsec.xs.network.Result a;

            public RunnableC0000a(com.netease.mobsec.xs.network.Result result) {
                this.a = result;
            }

            @Override // java.lang.Runnable
            public void run() {
                com.netease.mobsec.xs.NEDevice.a.this.c.onResult(this.a);
            }
        }

        public a(java.lang.String str, java.lang.String str2, com.netease.mobsec.xs.NECallback nECallback) {
            this.a = str;
            this.b = str2;
            this.c = nECallback;
        }

        @Override // java.lang.Runnable
        public void run() {
            com.netease.mobsec.xs.NEDevice nEDevice = com.netease.mobsec.xs.NEDevice.this;
            java.lang.String str = this.a;
            java.lang.String str2 = this.b;
            android.os.Handler handler = com.netease.mobsec.xs.NEDevice.a;
            com.netease.mobsec.xs.NEDevice.a.post(new com.netease.mobsec.xs.NEDevice.a.RunnableC0000a(nEDevice.a(str, str2)));
        }
    }


    public static class Holder {
        public static final com.netease.mobsec.xs.NEDevice a = new com.netease.mobsec.xs.NEDevice();
    }

    public static java.lang.String a194ba(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 149) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 107) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static com.netease.mobsec.xs.NEDevice get() {
        return com.netease.mobsec.xs.NEDevice.Holder.a;
    }

    public final com.netease.mobsec.xs.network.Result a(java.lang.String str, java.lang.String str2) {
        try {
            e0.c();
            return this.d.a(e0.a(), e0.b(), str, str2 == null ? "" : str2,
                    true, false, false, false, false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return com.netease.mobsec.xs.network.Result.a(1000);
        }
    }

    public java.lang.String getSDKVersion() {
        return a194ba(new char[]{235, 157, 202, 216, 11, 'o', '{'}, "ͩ˦͵ʌˤʹ");
    }

    public final com.netease.mobsec.xs.network.Result getToken(java.lang.String str) {
        return getToken(str, "");
    }

    public synchronized int init(android.content.Context context) {
        if (context == null) {
            return 1007;
        }
        if (com.netease.mobsec.xs.f.o(context) && !com.netease.mobsec.xs.f.a()) {
            if (this.c) {
                return 200;
            }
            try {
                com.netease.mobsec.xs.internal.NativeLibraryLoader.load(context, a194ba(new char[]{134, 196, 165, '>', 251}, "͎ͷ̺ͺ̷ʐ"));
                try {
                    this.c = com.netease.mobsec.xs.q.a(context);
                    this.d = new com.netease.mobsec.xs.s(context.getApplicationContext(), a194ba(new char[]{173, '{', 175, 'R', 223, 'O', '<', '\b', 217, ':', ')', 151, 156, 128, 168, 128, 16, 206, 228, 'S', 168, 146, '_', 207, 196, 217, '8', 'R', 208, 148, 27, 170, '`', 128, 16, 'l', 29}, "̡ʃˍʎ̈ʬ"), 5000);
                } catch (java.lang.Exception unused) {
                }
                return !this.c ? 1011 : 200;
            } catch (java.lang.Exception unused2) {
                return 1010;
            }
        }
        return 1008;
    }

    public final com.netease.mobsec.xs.network.Result getToken(java.lang.String str, java.lang.String str2) {
        int i;
        if (!this.c) {
            i = 1003;
        } else if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            i = 1006;
        } else {
            if (!android.text.TextUtils.isEmpty(str) && str.length() == 32) {
                return a(str, str2);
            }
            i = 1007;
        }
        return com.netease.mobsec.xs.network.Result.a(i);
    }

    public void getToken(java.lang.String str, com.netease.mobsec.xs.NECallback nECallback) {
        getToken(str, "", nECallback);
    }

    public void getToken(java.lang.String str, java.lang.String str2, com.netease.mobsec.xs.NECallback nECallback) {
        int i;
        if (nECallback == null) {
            throw new com.netease.mobsec.xs.NEDeviceErrorException(a194ba(new char[]{'t', 'B', 200, 205, 215, 'j', 200, '>', 'K', 'u', 153, 224, 19, 254, '\"', 173, 216, 'S', 216, 191, 195, 180, 'H', '#', 184, 132, 'K', 239, 'I', 160, 'F', 222, 'd', 't', 142}, "ˁͻɶ˯ˌʖ"));
        }
        if (!this.c) {
            i = 1003;
        } else {
            if (!android.text.TextUtils.isEmpty(str) && str.length() == 32) {
                try {
                    b.execute(new com.netease.mobsec.xs.NEDevice.a(str, str2, nECallback));
                    return;
                } catch (java.lang.Exception unused) {
                    nECallback.onResult(com.netease.mobsec.xs.network.Result.a(1008));
                    return;
                }
            }
            i = 1007;
        }
        nECallback.onResult(com.netease.mobsec.xs.network.Result.a(i));
    }
}
