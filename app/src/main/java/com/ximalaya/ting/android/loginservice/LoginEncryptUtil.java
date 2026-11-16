package com.ximalaya.ting.android.loginservice;
import android.content.Context;
import android.content.ContextWrapper;
import java.util.Map;
import java.util.TreeMap;
/** JNI names are the ABI of the supplied login protocol component. */
public final class LoginEncryptUtil {
    static { System.loadLibrary("login_encrypt"); }
    private native String AFlIEghJkK(Context context, boolean test, String input);
    private native String eXYTbsvYIn(String input);
    public String encrypt(String value) { return eXYTbsvYIn(value); }
    public String sign(Context context, Map<String,String> fields) {
        StringBuilder input = new StringBuilder();
        for (Map.Entry<String,String> entry : new TreeMap<>(fields).entrySet())
            input.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        // This JNI argument selects the passport protocol product, not the installed app identity.
        // The independent package otherwise selects a different product's signing configuration.
        // Keep this adapter private to native signing; HTTP impl/device and Android identity
        // continue to use the real application context.
        return AFlIEghJkK(new PassportProductContext(context), false, input.toString());
    }

    private static final class PassportProductContext extends ContextWrapper {
        PassportProductContext(Context context) { super(context); }
        @Override public String getPackageName() { return "com.ximalaya.ting.android"; }
    }
}
