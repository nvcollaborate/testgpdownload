package com.testappnongpdownload.app;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import com.google.android.gms.auth.GoogleAuthUtil;

public class AuthSupportActivity extends Activity {

    private GoogleAuthenticator googleAuthenticator;
    private SharedPreferences prefs;

    private boolean shouldFinish = false;
    private boolean askForAccount = true;
    private AsyncTask authAsync;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.authsupportlayout);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        googleAuthenticator = new GoogleAuthenticator(this, prefs);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (authAsync != null) {
            if (authAsync.getStatus() != AsyncTask.Status.FINISHED) {
                authAsync.cancel(true);
            }
            authAsync = null;
        }


        if (shouldFinish) {
            // finishing activity cause googleAuthenticator.initialize will not return to normal flow
            // once user is prompted for password, but after prompting when user will finish auth activity
            // this activity will come to onResume and here in any case we need to finish it.
            new AsyncTask<Void, Void, Void>() {

                @Override
                protected Void doInBackground(Void... params) {
                    googleAuthenticator.getTokenDirect(AuthSupportActivity.this, true);
                    return null;
                }

                @Override
                protected void onPostExecute(Void param) {
                    super.onPostExecute(param);
                    AuthSupportActivity.this.finish();
                }
            }
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            askForAccess();
        }
    }

    private void askForAccess() {

        if (!hasGoogleAccount(this)) {
            if (askForAccount) {
                askForAccount = false;
                googleAuthenticator.reset();
                googleAuthenticator.addAccount(AuthSupportActivity.this);
            } else {
                AuthSupportActivity.this.finish();
            }
            return;
        }

        authAsync = new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... params) {
                shouldFinish = true;
                googleAuthenticator.initialize(AuthSupportActivity.this, true);
                return null;
            }

            @Override
            protected void onPostExecute(Void param) {
                super.onPostExecute(param);
                shouldFinish = false;
                AuthSupportActivity.this.finish();
            }
        }
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    }

    public static boolean hasGoogleAccount(Context context) {
        AccountManager am = AccountManager.get(context);
        Account[] accounts = am.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
        return accounts != null && accounts.length > 0;
    }
}
