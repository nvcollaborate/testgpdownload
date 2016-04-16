package com.testappnongpdownload.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import com.google.protobuf.nano.MessageNano;
import com.testappnongpdownload.app.playstoreapiutils.Play;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class GooglePlayStoreApi {

    private static final Logger LOG = LoggerFactory.getLogger(GooglePlayStoreApi.class);
    public static final Pattern DOWNLOAD_URL_PATTERN = Pattern.compile("(?i)https?://[^:]+");

    private static class PlayStoreApp {
        private String packageName;
        private String downloadUrl;
        private String marketDa;

        PlayStoreApp(String packageName, String downloadUrl, String marketDa) {
            this.packageName = packageName;
            this.downloadUrl = downloadUrl;
            this.marketDa = marketDa;
        }
    }

    /**
     * Authenticator.
     */
    private GoogleAuthenticator authenticator;

    /**
     * Request vars.
     */
    private final int sdkVersion;

    private final String deviceAndSdkVersion;
    private final String operator;
    private final String operatorNumeric;
    private final String locale;
    private final String country;

    /**
     * Request url.
     */
    private static final String REQUEST_URL = "https://android.clients.google.com/market/api/ApiRequest";
    //private final String REQUEST_URL = "https://android.clients.google.com/market/api/ApiRequest";

    /**
     * Request version.
     */
    private static final int REQUEST_VERSION = 2;

    Context context;

    /**
     * Constructor.
     */
    public GooglePlayStoreApi(Context context, GoogleAuthenticator authenticator) {
        this.context = context;
        this.authenticator = authenticator;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        sdkVersion = Integer.parseInt(prefs.getString(AppConstants.SDK_VERSION, "8013013"));
        deviceAndSdkVersion = prefs.getString(AppConstants.DEVICE_AND_SDK_VERSION, "Mako:18");
        operator = prefs.getString(AppConstants.OPERATOR, "Airtel");
        operatorNumeric = prefs.getString(AppConstants.OPERATOR_NUMERIC, "31020");
        locale = prefs.getString(AppConstants.LOCALE, "en");
        country = prefs.getString(AppConstants.COUNTRY, "us");
    }

    public void downloadApk(String packageName, File file) throws Exception {
        PlayStoreApp app = getApp(packageName);

        boolean supportsRange = true;
        long fromRange = 0;
        if (file.exists()) {
            fromRange = file.length();
        }

        File metaFile = new File(file + ".google-play-meta");
        HttpURLConnection conn = null;
        int redirects = 0;
        try {

            long contentLength = -1;
            BufferedReader in = null;
            try {
                in = new BufferedReader(new FileReader(metaFile));
                String contentLengthStr = in.readLine();
                if (contentLengthStr != null && !contentLengthStr.trim().isEmpty()) {
                    contentLength = Long.parseLong(contentLengthStr);
                }
            } catch (FileNotFoundException e) {
                fromRange = 0;
                file.delete();
            } finally {
                closeQuietly(in);
            }
            if (file.exists() && contentLength == file.length()) {
                //callback.downloadProgress(1.0f);
                metaFile.delete();
                return;
            }

            URL url = new URL(app.downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            String cookies = "MarketDA=" + app.marketDa;

            while (true) {
                if (redirects > 5) {
                    throw new IOException("max redirects tried");
                }
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                if (supportsRange) {
                    conn.setRequestProperty("Range", "bytes=" + fromRange + "-");
                }
                conn.setRequestProperty("Cookie", cookies);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(60000);
                conn.connect();

                switch (conn.getResponseCode()) {
                    case HttpURLConnection.HTTP_MOVED_PERM:
                    case HttpURLConnection.HTTP_MOVED_TEMP:
                        url = new URL(conn.getHeaderField("Location"));
                        redirects++;
                        disconnectQuietly(conn);
                        continue;

                    case HttpURLConnection.HTTP_NOT_MODIFIED:
                        fromRange = 0;
                        supportsRange = false;
                        disconnectQuietly(conn);
                        continue;

                    default:
                        break;
                }

                break;
            }

            int respCode = conn.getResponseCode();

            if (respCode != HttpURLConnection.HTTP_OK && respCode != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("unexpected status code : " + conn.getResponseCode() + ", "
                        + conn.getResponseMessage());
            }

            long originalContentLength = -1;
            contentLength = 100L * 1024 * 1024;
            String contentLengthStr = conn.getHeaderField("Content-Length");
            if (contentLengthStr != null) {
                try {
                    contentLength = Long.parseLong(contentLengthStr);
                    originalContentLength = contentLength;
                } catch (NumberFormatException e) {
                    // ignore
                }
            }

            long total = 0;
            if (fromRange == 0 || respCode != HttpURLConnection.HTTP_PARTIAL) {
                fromRange = 0;
                file.delete();

                if (supportsRange) {
                    writeString(Long.toString(originalContentLength), metaFile);
                }
            } else {
                contentLength += fromRange;
                total = fromRange;
                //analytics.logEvent(CoreAnalyticsEvent.APP_DOWNLOAD_PARTIAL_RETRY, app.packageName);
            }

            if (!supportsRange) {
                //analytics.logEvent(CoreAnalyticsEvent.APP_DOWNLOAD_PARTIAL_NOT_SUPPORTED, app.packageName);
            }

            RandomAccessFile fos = new RandomAccessFile(file, "rw");
            fos.seek(fromRange);
            try {
                InputStream is = conn.getInputStream();

                byte[] buffer = new byte[1024];
                int n = 0;
                while ((n = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, n);
                    total += n;
                    float percent = ((float) total) / contentLength;
                    percent = (float) Math.min(percent, 1.0);
                    //callback.downloadProgress(percent);
                }
                fos.close();
                is.close();
                //callback.downloadProgress(1.0f);
            } finally {
                closeQuietly(fos);
            }
            metaFile.delete();
        } catch (IOException e) {
            LOG.info("unable to download app", e);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception e) {
                }
            }
        }
    }

    public boolean appExists(String packageName) {
        try {
            PlayStoreApp app = getApp(packageName);
            return app.downloadUrl != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private PlayStoreApp getApp(String packageName) throws Exception {
        byte[] protoBuf = buildProtoBuf(packageName);

        try {
            HttpPost post = new HttpPost(REQUEST_URL);
            final String protoBufString = Base64.encodeToString(protoBuf, Base64.DEFAULT);
            LOG.debug("protoBufString {}", protoBufString);

            post.addHeader("Content-Type", "application/x-www-form-urlencoded");
            post.setEntity(
                    new StringEntity(String.format(
                            "version=%d&request=%s",
                            REQUEST_VERSION,
                            protoBufString
                    ))
            );

            HttpClient client = new DefaultHttpClient();
            HttpResponse response = client.execute(post);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                String msg = String.format(Locale.ENGLISH, "Server responded with status : %d / %s", statusCode,
                        response.getStatusLine().getReasonPhrase());
                if (statusCode == 403) {
                    authenticator.reset();
                    throw new GoogleAuthenticationException(msg);
                }
                LOG.debug("Exception calling google api {}", msg);
                throw new Exception(msg);
            }


            final HttpEntity httpEntity = response.getEntity();
            LOG.debug("HttpEntity {}", httpEntity);

            byte[] bin = EntityUtils.toByteArray(response.getEntity());

            ByteArrayInputStream bais = new ByteArrayInputStream(bin);
            LOG.debug("bin: {}, bais: {}", bin.length, bais.available());
            GZIPInputStream gzis = new GZIPInputStream(bais);
            BufferedInputStream inputStream = new BufferedInputStream(gzis);

            String line = new String(readFully(inputStream), Charset.forName("UTF-8"));

            return new PlayStoreApp(packageName, extractDownloadPath(line), extractMarketDa(line));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**`
     * Manually build a protobuf request.
     */
    private byte[] buildProtoBuf(String packageName) throws GoogleAuthenticationException {
        // Generate byte array from the auth subtoken
        if (authenticator == null) {
            LOG.info("Ajitesh : authenticator is null");
        } else if (authenticator.getToken() == null) {
            LOG.info("Ajitesh : token is null");
        }
        byte[] authTokenBytes = authenticator.getToken().getBytes(Charset.forName("UTF-8"));
        byte[] authExtraBytes = {16, 1}; // Not sure why these have to be appended, but hey ho
        byte[] authBytes = new byte[authTokenBytes.length + authExtraBytes.length];

        System.arraycopy(authTokenBytes, 0, authBytes, 0, authTokenBytes.length);
        System.arraycopy(authExtraBytes, 0, authBytes, authTokenBytes.length, authExtraBytes.length);

        // Build a (mostly correct) request protobuf
        Play.App app = new Play.App();
        app.authSubToken = authBytes;
        app.version = sdkVersion;
        app.androidId = authenticator.getGsfId();
        app.deviceAndSdkVersion = deviceAndSdkVersion;
        app.userLanguage = locale;
        app.userCountry = country;
        app.operatorAlpha = operator;
        app.simOperatorAlpha = operator;
        app.operatorNumeric = operatorNumeric;
        app.simOperatorNumeric = operatorNumeric;

        Play.RequestContext requestContext = new Play.RequestContext();
        requestContext.app = new Play.App[1];
        requestContext.app[0] = app;

        byte[] partialProtoBytes = MessageNano.toByteArray(requestContext);
        partialProtoBytes[4] -= 2;

        ArrayList<Byte> packageNameByteList = new ArrayList<Byte>();
        packageNameByteList.add((byte) 19);
        packageNameByteList.add((byte) 82);
        packageNameByteList.add((byte) (packageName.length() + 2));
        packageNameByteList.add((byte) 10);
        packageNameByteList.add((byte) packageName.length());
        for (int i = 0; i < packageName.length(); i++) {
            packageNameByteList.add((byte) packageName.charAt(i));
        }
        packageNameByteList.add((byte) 20);

        byte[] packageNameBytes = new byte[packageNameByteList.size()];
        for (int i = 0; i < packageNameByteList.size(); i++) {
            packageNameBytes[i] = packageNameByteList.get(i);
        }

        byte[] protoBytes = new byte[partialProtoBytes.length + packageNameBytes.length];
        System.arraycopy(partialProtoBytes, 0, protoBytes, 0, partialProtoBytes.length);
        System.arraycopy(packageNameBytes, 0, protoBytes, partialProtoBytes.length, packageNameBytes.length);

        return protoBytes;
    }

    /**
     * Manually extract the download path from the protobuf response.
     */
    private String extractDownloadPath(String str) {
        Matcher m = DOWNLOAD_URL_PATTERN.matcher(str);

        LOG.trace("DOWNLOAD_URL_PATTERN {}, in coming: {}", DOWNLOAD_URL_PATTERN, str);

        if (!m.find()) {
            LOG.info("Download path not found");
        }

        final String downloadPath = m.group(0);

        LOG.trace("DOWNLOAD_URL_PATTERN {}, in coming: {}, Download Path: {}", DOWNLOAD_URL_PATTERN, str, downloadPath);
        return downloadPath;
    }

    /**
     * Manually extract the market da from the protobuf response.
     */
    private String extractMarketDa(String str) {
        boolean capture = false;
        StringBuilder sb = new StringBuilder();

        for (int i = str.lastIndexOf(0x014); i < str.length(); i++) {
            byte b = (byte) str.charAt(i);
            if (b == 0x014) {
                capture = true;
            } else if (capture && b == 0x0c) {
                break;
            } else if (capture) {
                sb.append(str.charAt(i));
            }
        }

        if (sb.length() == 0) {
            LOG.info("MarketDA not found");
        }

        return sb.toString();
    }

    public static void closeQuietly(Closeable closeable) {
        if(closeable != null) {
            try {
                closeable.close();
            } catch (IOException var2) {
                ;
            } catch (Error var3) {
                ;
            }

        }
    }

    public static void disconnectQuietly(HttpURLConnection conn) {
        if(conn != null) {
            try {
                conn.disconnect();
            } catch (Exception var2) {
                ;
            }
        }

    }

    public static int copy(InputStream in, OutputStream out) throws IOException {
        BufferedInputStream bin = new BufferedInputStream(in);
        BufferedOutputStream bout = new BufferedOutputStream(out);
        int total = 0;

        int n;
        for(byte[] data = new byte[1024]; (n = bin.read(data)) > 0; total += n) {
            bout.write(data, 0, n);
            bout.flush();
        }

        bout.flush();
        return total;
    }

    public static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    public static void writeString(String data, OutputStream out) throws IOException {
        out.write(data.getBytes(Charset.forName("UTF-8")));
        out.flush();
    }

    public static void writeString(String data, File out) throws IOException {
        FileOutputStream fout = new FileOutputStream(out);

        try {
            writeString(data, (OutputStream)fout);
        } finally {
            closeQuietly(fout);
        }

    }

}
