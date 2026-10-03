package com.netease.mobsec.xs;



public class p {
    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 105) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 151) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String a(android.content.Context context) {
        if (!a(new char[]{'P'}, "̗ͬ͞ɠ́ˀ").equals(com.netease.mobsec.xs.f.a(a(new char[]{'\f', 254, 189, 188, 'b', '{', 'u', 135, 165, 4, 29, 11, 27, 222, 'b', 30, 5, 208, 203, '~', '\"', 'd', '\"', '0', 178, '?', 28, 156, 131, 240, '4', 156, 227, 228}, "ʹʾʳʺʿ̓"), a(new char[]{239}, "̃ɺˑɘɢ˪")))) {
            return null;
        }
        try {
            android.database.Cursor cursorO = com.ljyh.mei.data.network.sdk.RuntimeAccess.O(context.getContentResolver(), android.net.Uri.parse(a(new char[]{234, 157, 'Z', '\r', 151, 160, 244, '>', 130, 'f', '.', 192, 26, 197, '+', 143, 185, 192, 'R', 'v', 235, 180, 150, '\'', 11, 129, 180, 14, 137, 11, 250, 164, '\\', 231, 19, 'a', 27, 173, 5, 175, 'W', 218, '+', 21, 143, 238, '6', 132, 'n', 161, 23}, "\u0378˯˸̸̕ˆ")), null, null, null, null, "com/netease/mobsec/xs/p.class:a:(Landroid/content/Context;)Ljava/lang/String; line-2");
            if (cursorO == null || !cursorO.moveToFirst()) {
                com.netease.mobsec.xs.f.a(cursorO);
            } else {
                java.lang.String string = cursorO.getString(cursorO.getColumnIndexOrThrow(a(new char[]{3, 166, 24, 133, 30}, "ʭ\u0378̈͵͐ʳ")));
                com.netease.mobsec.xs.f.a(cursorO);
                if (!android.text.TextUtils.isEmpty(string)) {
                    return string;
                }
            }
        } catch (java.lang.Exception unused) {
        }
        return null;
    }
}
