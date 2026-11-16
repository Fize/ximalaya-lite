package com.shixi.client;
import android.app.Instrumentation;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public class UpgradeAccountVerification extends Instrumentation {
    private String phase;
    @Override public void onCreate(Bundle args) { super.onCreate(args); phase=args.getString("phase",""); start(); }
    @Override public void onStart() {
        Bundle out=new Bundle();
        try {
            AtomicFile file=new AtomicFile(new File(getTargetContext().getNoBackupFilesDir(),"account-session-v1"));
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            SecretKey key=(SecretKey)ks.getKey("shixi.account.v1",null);
            byte[] aad=(getTargetContext().getPackageName()+":account:v1").getBytes(StandardCharsets.UTF_8);
            if (phase.equals("seed")) {
                if(file.getBaseFile().exists()) throw new IllegalStateException("fixture requires empty account");
                if(key==null) { KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore"); gen.init(new KeyGenParameterSpec.Builder("shixi.account.v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());key=gen.generateKey(); }
                Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key); c.updateAAD(aad);
                byte[] data=c.doFinal(new JSONObject().put("uid",874211L).put("token","synthetic-upgrade-only").toString().getBytes(StandardCharsets.UTF_8));
                JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(data,Base64.NO_WRAP));
                FileOutputStream stream=file.startWrite(); try {stream.write(envelope.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception e){file.failWrite(stream);throw e;}
            } else if(phase.equals("verify")) {
                if(key==null)throw new IllegalStateException("keystore key missing");
                JSONObject envelope=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8)); Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));c.updateAAD(aad);
                JSONObject plain=new JSONObject(new String(c.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
                if(plain.getLong("uid")!=874211L || !plain.getString("token").equals("synthetic-upgrade-only"))throw new IllegalStateException("account mismatch");file.delete();
            } else throw new IllegalArgumentException("phase");
            out.putString("result","PASS synthetic encrypted account "+phase);finish(-1,out);
        } catch(Throwable t) {out.putString("failure",t.toString());finish(0,out);}
    }
}
