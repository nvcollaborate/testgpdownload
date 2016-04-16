package com.testappnongpdownload.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import java.io.File;
import java.lang.reflect.Method;


public class MainActivity extends Activity implements View.OnClickListener {
    private Button installButton;
    private EditText medit;
    private String packageName;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent intent = new Intent(this, AuthSupportActivity.class);
        this.startActivity(intent);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        installButton = (Button) findViewById(R.id.buttonInstall);
        installButton.setOnClickListener(this);
        medit = (EditText) findViewById(R.id.packageedit);
    }

    @Override
    public void onClick(View v) {
        packageName = medit.getText().toString();
        final File file = new File(String.valueOf(getDataFileDir(this, packageName + ".apk")));
        final File tmpFile = new File(file.getAbsolutePath());
        GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator(this, prefs);
        final GooglePlayStoreApi playStoreApi = new GooglePlayStoreApi(this, googleAuthenticator);
        try {
            new AsyncTask<Void, Void, Void>() {

                @Override
                protected Void doInBackground(Void... params) {
                    try {
                        playStoreApi.downloadApk(packageName, tmpFile);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void param) {
                    super.onPostExecute(param);
                    Log.d("Ajitesh", "download complete");
                    file.setReadable(true, false);
                    file.setExecutable(true, false);
                    installApkNormally(file);
                    //installApkSilently(file);
                }
            }
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static File getDataFileDir(Context context, String uniqueName) {
        return new File(context.getFilesDir().getPath() + File.separator + uniqueName);
    }

    private void installApkNormally(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void installApkSilently(File file) {
        PackageManager packageManager = this.getPackageManager();
        try {
            Class<?> clazz = Class.forName("android.content.pm.IPackageInstallObserver");
            Method method = packageManager.getClass().getMethod("installPackage",
                    Uri.class,
                    clazz,
                    int.class,
                    String.class
            );
            method.invoke(packageManager, Uri.fromFile(file), null, 0x82, AppConstants.PACKAGE_NAME);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
