package com.testappnongpdownload.app;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings.Secure;
import com.google.android.gms.auth.GoogleAuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class GoogleAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleAuthenticator.class);

    /**
     * Context.
     */
    private Context context;

    /**
     * Auth sub-token.
     */
    private String subToken;

    /**
     * Preferences instance.
     */
    private SharedPreferences prefs;


    /**
     * Auth service to get token for.
     */
    private static final String AUTH_SERVICE = "androidsecure";

    /**
     * Auth account type.
     */
    private static final String AUTH_ACCOUNT_TYPE = "HOSTED_OR_GOOGLE";

    /**
     * Client login url.
     */
    private static final String AUTH_URL = "https://www.google.com/accounts/ClientLogin";

    /**
     * Constructor.
     */
    public GoogleAuthenticator(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    public void initialize(Activity activity, boolean promptPlayStore) {
        getToken(activity, promptPlayStore);
    }

    public void initialize(Activity activity) {
        getToken(activity);
    }


    public void addAccount(Activity activity) {
        AccountManager acm = AccountManager.get(activity);
        acm.addAccount("com.google", null, null, null, activity,
                new AccountManagerCallback<Bundle>() {
                    @Override
                    public void run(AccountManagerFuture<Bundle> future) {
                        try {
                            future.getResult();
                        } catch (OperationCanceledException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (AuthenticatorException e) {
                            e.printStackTrace();
                        } finally {
                            return;
                        }
                    }
                }, null);
    }

    public void getTokenDirect(Activity activity, final boolean checkAppsToinstall) {
        AccountManager am = AccountManager.get(activity);
        Account[] accounts = am.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
        LOG.info("Ajitesh : num accounts " + accounts.length);
        final CountDownLatch latch = new CountDownLatch(1);
        LOG.info("Ajitesh : calling getAuthToken");
        am.getAuthToken(accounts[0], AUTH_SERVICE, null, false, new AccountManagerCallback<Bundle>() {
            @Override
            public void run(AccountManagerFuture<Bundle> future) {
                try {
                    Bundle authTokenBundle = future.getResult();
                    subToken = authTokenBundle.getString(AccountManager.KEY_AUTHTOKEN).toString();
                    LOG.info("Ajitesh : token " + subToken);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString(AppConstants.APP_STORE_GOOGLE_TOKEN_KEY, subToken);
                    editor.putLong(AppConstants.ONE_CLICK_PROMPT_START_TIME, 0);
                    editor.putBoolean(AppConstants.UI_DONOT_SHOW_GPLAY_ACC_NOTIF, false);
                    editor.apply();
                } catch (OperationCanceledException e) {
                    LOG.info("Ajitesh : unable to getTokenDirect subToken", e);
                    //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_CANCELLED);
                    reset();
                } catch (AuthenticatorException e) {
                    LOG.info("Ajitesh : unable to getTokenDirect subToken", e);
                    //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_CANCELLED);
                    reset();
                } catch (Exception e) {
                    LOG.info("Ajitesh : unable to getTokenDirect subToken", e);
                    //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_CANCELLED);
                    reset();
                    //Network error or unknown issue so let's get more chances with keeping 0
                    prefs.edit().putLong(AppConstants.ONE_CLICK_PROMPT_START_TIME, 0).apply();
                } finally {
                    latch.countDown();
                    return;
                }
            }
        }, null);
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    public void getTokenDirect(Activity activity) {
        getTokenDirect(activity, false);
    }

    private String getToken(Activity activity, boolean checkAppsToinstall) {
        subToken = prefs.getString(AppConstants.APP_STORE_GOOGLE_TOKEN_KEY, null);
        if (subToken != null) {
            return subToken;
        }
        if (activity != null) {
            // Attempt to use OAuth
            try {
                AccountManager am = AccountManager.get(activity);
                Account[] accounts = am.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
                AccountManagerFuture<Bundle> accountManagerFuture;
                // getAuthToken can be used on main thread
                accountManagerFuture = am.getAuthToken(accounts[0], AUTH_SERVICE, null, activity, null, null);
                // this accountManagerFuture should not be used on main thread
                Bundle authTokenBundle = accountManagerFuture.getResult();
                subToken = authTokenBundle.getString(AccountManager.KEY_AUTHTOKEN).toString();
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(AppConstants.APP_STORE_GOOGLE_TOKEN_KEY, subToken);
                editor.putLong(AppConstants.ONE_CLICK_PROMPT_START_TIME, 0);
                editor.putBoolean(AppConstants.UI_DONOT_SHOW_GPLAY_ACC_NOTIF, false);
                editor.apply();
                //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_ENABLED);
            } catch (OperationCanceledException e) {
                LOG.info("Ajitesh : unable to getTokenDirect subToken", e);
                //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_CANCELLED);
                reset();
            } catch (AuthenticatorException e) {
                LOG.info("Ajitesh : unable to getTokenDirect subToken", e);
                //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_CANCELLED);
                reset();
            } catch (Exception e) {
                LOG.info("Ajitesh : unable to getTokenDirect subToken", e);
                //analytics.logEvent(CoreAnalyticsEvent.GOOGLE_AUTH_CANCELLED);
                reset();
                prefs.edit().putLong(AppConstants.ONE_CLICK_PROMPT_START_TIME, 0).apply();
            }
        }
        return subToken;
    }


    private String getToken(Activity activity) {
        return getToken(activity, false);
    }

    /**
     * Get authentication token.
     */
    public String getToken() throws GoogleAuthenticationException {
        return getToken(null);
    }

    public boolean isInitialized() {
        try {
            return getToken() != null;
        } catch (GoogleAuthenticationException e) {
            return false;
        }
    }

    public void reset() {
        subToken = null;
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(AppConstants.APP_STORE_GOOGLE_TOKEN_KEY);
        editor.remove(AppConstants.APP_STORE_GOOGLE_GSFID_KEY);
        editor.apply();
    }

    /**
     * Get android id.
     */
    public String getAndroidId() {
        return Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
    }

    /**
     * Get google services framework id.
     */
    public String getGsfId() {
        // Return preference if set
        String gsfid = prefs.getString(AppConstants.APP_STORE_GOOGLE_GSFID_KEY, null);

        if (gsfid != null && !gsfid.equals("")) {
            return gsfid;
        }

        // Otherwise attempt to get it from google services
        Cursor c = context.getContentResolver().query(
                Uri.parse("content://com.google.android.gsf.gservices"),
                null,
                null,
                new String[]{"android_id"},
                null
        );

        if (c == null || !c.moveToFirst() || c.getColumnCount() < 2) {
            return null;
        }

        try {
            String id = Long.toHexString(Long.parseLong(c.getString(1)));
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(AppConstants.APP_STORE_GOOGLE_GSFID_KEY, id);
            editor.apply();

            return id;
        } catch (NumberFormatException e) {
            return null;
        } finally {
            c.close();
        }
    }
}
