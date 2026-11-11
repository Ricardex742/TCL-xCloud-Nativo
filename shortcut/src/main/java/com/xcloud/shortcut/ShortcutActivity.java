package com.xcloud.shortcut;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** A launcher entry only. All authentication, catalog validation and streaming stay in xCLOUD. */
public final class ShortcutActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Bundle metadata=getPackageManager().getApplicationInfo(getPackageName(),PackageManager.GET_META_DATA).metaData;
            String title=metadata.getString("xcloud_title");
            if(title==null || title.length()>128 || title.contains("/")) throw new IllegalArgumentException();
            Uri uri=new Uri.Builder().scheme("xcloud").authority("play").appendPath(title).build();
            startActivity(new Intent(Intent.ACTION_VIEW,uri).setPackage("com.xcloud.tclnative")
                .putExtra("shortcut_request",java.util.UUID.randomUUID().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch(Exception error) {
            Toast.makeText(this,"Instale ou atualize o xCLOUD para abrir este jogo.",Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
