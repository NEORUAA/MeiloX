package com.netease.mobsec.xs;



public class b0 {
    public static volatile boolean a;
    public static volatile int b;


    public static class a implements android.hardware.display.DisplayManager.DisplayListener {
        public final android.hardware.display.DisplayManager a;
        public final java.util.Set<java.lang.Integer> b = new java.util.HashSet();

        public a(android.hardware.display.DisplayManager displayManager) {
            this.a = displayManager;
        }

        public final void a() {
            com.netease.mobsec.xs.b0.b = 0;
            java.util.HashSet hashSet = new java.util.HashSet(this.b);
            if (hashSet.size() <= 0) {
                return;
            }
            java.util.Iterator it = hashSet.iterator();
            while (it.hasNext()) {
                java.lang.Integer num = (java.lang.Integer) it.next();
                if (num.intValue() != 0) {
                    try {
                        if (this.a.getDisplay(num.intValue()) == null) {
                            com.netease.mobsec.xs.b0.b = num.intValue();
                        }
                    } catch (java.lang.Exception unused) {
                    }
                }
            }
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayAdded(int i) {
            synchronized (this) {
                try {
                    this.b.add(java.lang.Integer.valueOf(i));
                    a();
                } catch (java.lang.Exception unused) {
                }
            }
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayChanged(int i) {
            synchronized (this) {
                try {
                    this.b.add(java.lang.Integer.valueOf(i));
                    a();
                } catch (java.lang.Exception unused) {
                }
            }
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayRemoved(int i) {
            synchronized (this) {
                try {
                    this.b.remove(java.lang.Integer.valueOf(i));
                    a();
                } catch (java.lang.Exception unused) {
                }
            }
        }
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 139) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 117) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }
}
