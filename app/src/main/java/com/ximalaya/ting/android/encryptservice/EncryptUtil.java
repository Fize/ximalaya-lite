package com.ximalaya.ting.android.encryptservice;
import android.content.Context;
import android.content.pm.PackageInfo;
import java.util.List;
import java.util.Map;
/** Original JNI declarations retain the supplied signing library ABI; no analytics SDK. */
public final class EncryptUtil {
    public static boolean f32062a = true;
    static { System.loadLibrary("encrypt"); }
    private native String BAOResUOvR(Context context, String[] strArr, String[] strArr2, int i, boolean z);
    private native String CduekLxHQQ(Context context, String str);
    private native String DBlPuHwuEE(Context context, String[] strArr, String[] strArr2, int i, boolean z);
    private native String GEEemZwMvC(Context context, String str);
    private native String HWwPmjpYCb(Context context, String str);
    private native byte[] IpXobGzBdC(Context context, byte[] bArr, byte[] bArr2);
    private native void JJiCCmabYm(Context context, long j);
    private native String JPXkkYluOD(Context context, boolean z, String str);
    private native String KDBRBOeaVI(Context context, String str, boolean z);
    private native byte[] MskrWrSJQZ(Context context, byte[] bArr, byte[] bArr2);
    private native String NHOJFaNeVY(Context context, String str);
    private native long OJljmYtuod(Context context);
    private native String OORARNmSWI(Context context, boolean z, boolean z2);
    private native byte[] PmlEZSuoIa(Context context, long j, byte[] bArr, int i);
    private native String QByWzxMNka(Context context, String str) throws Exception;
    private native String QnlwMbydoS(Context context, String str);
    private native String RULEjjKQFe(Context context, String str) throws Exception;
    private native byte[] RkTYvhdOeo(Context context, byte[] bArr, byte[] bArr2, byte[] bArr3);
    private native String RxTkyDoELD(Context context, String str);
    private native String TMHzidqCMk(Context context, String str);
    private native String UNZIWbHFBV(Context context, String str, String str2);
    private native List<PackageInfo> UqxRtnIrcr(Context context, int i);
    private native String WDfiUGBkMs(Context context, String[] strArr, String[] strArr2, int i, boolean z);
    private native byte[] bXSFeCyFMU(Context context, byte[] bArr) throws Exception;
    private native String cYWOoJESuO(Context context, String str);
    private native byte[] cdQCQXVFDT(Context context, long j, byte[] bArr, int i);
    private native byte[] dqiqRQFpQn(Context context, byte[] bArr, byte[] bArr2);
    private native byte[] ePGYFRhCLv(Context context, byte[] bArr) throws Exception;
    private native String hqpYzLYWds(Context context, String[] strArr, String[] strArr2, int i, boolean z);
    private native String iEswPoAjDC(Context context, String str);
    private native int ihIEOhwumL(Context context);
    private native String iwYUxeHLMo(Context context);
    private native String jBCUXLhdiT(Context context, String str) throws Exception;
    private native String krYmrcAbvA(Context context, String[] strArr, String[] strArr2, int i, boolean z);
    private native String lTunxMTWnc(Context context, String str) throws Exception;
    private native byte[] lsGbiddNcK(Context context, byte[] bArr) throws Exception;
    private native byte[] mjeDkmLiUZ(Context context, byte[] bArr);
    private native String nNpJXBTUnk(Context context, String str) throws Exception;
    private native String pmTQGJMvJa(Context context);
    private native String rsKTJLgiZD(Context context);
    private native String sfDEESzQXQ(Context context, String[] strArr, String[] strArr2, int i);
    private native byte[] uYNwhHAoVJ(Context context, byte[] bArr, byte[] bArr2);
    private native byte[] uqdMsgeZQB(Context context, byte[] bArr, byte[] bArr2);
    private native String veMzORjVkF(Context context, Map<String, String> map);
    private native String xKWKHbbjRy(Context context, String[] strArr, String[] strArr2, int i, boolean z);
    private native String xLySFQmJLz(Context context, String str);
    private native String yfuctYlsUF(Context context);
    public String[] paidMedia(Context context, String fileId, String ep) throws Exception {
        Context wrapper = context instanceof android.content.ContextWrapper ? context : new android.content.ContextWrapper(context);
        byte[] decoded = ePGYFRhCLv(wrapper, android.util.Base64.decode(fileId, android.util.Base64.DEFAULT));
        return new String[] { new String(decoded, java.nio.charset.StandardCharsets.UTF_8).trim(), lTunxMTWnc(wrapper, ep).trim() };
    }
    public String historySignature(Context context, Map<String,String> fields) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (Map.Entry<String,String> entry : fields.entrySet()) {
            if (entry.getKey()!=null && !entry.getKey().isEmpty() && entry.getValue()!=null && !entry.getValue().isEmpty()) {
                keys.add(entry.getKey()); values.add(entry.getValue());
            }
        }
        // The supplied ABI resolves ContextWrapper methods; Instrumentation may supply ContextImpl.
        Context wrapper = context instanceof android.content.ContextWrapper ? context : new android.content.ContextWrapper(context);
        return xKWKHbbjRy(wrapper, keys.toArray(new String[0]), values.toArray(new String[0]), keys.size(), false);
    }
}
