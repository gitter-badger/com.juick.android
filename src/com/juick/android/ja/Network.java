package com.juick.android.ja;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.util.Pair;
import com.juickadvanced.RESTResponse;
import com.juick.android.Utils;
import com.juick.android.juick.JuickAPIAuthorizer;
import com.juick.android.juick.JuickMicroBlog;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Created with IntelliJ IDEA.
 * User: san
 * Date: 11/9/12
 * Time: 12:46 AM
 * To change this template use File | Settings | File Templates.
 */
public class Network {

    public static RESTResponse postJSONHome(Context context, String path, String data) {
        RESTResponse ret = null;
        try {
            URL jsonURL = new URL("http://" + Utils.JA_ADDRESS + path);
            HttpURLConnection conn = (HttpURLConnection) jsonURL.openConnection();

            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.connect();

            OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());
            wr.write(data);
            wr.close();

            if (conn.getResponseCode() == 200) {
                InputStream inputStream = conn.getInputStream();
                ret = Utils.streamToString(inputStream, null);
                inputStream.close();
            } else {
                return new RESTResponse("HTTP "+conn.getResponseCode()+" " + conn.getResponseMessage(), false, null);
            }

            conn.disconnect();
            return ret;
        } catch (Exception e) {
            Log.e("getJSONHome", e.toString());
            return new RESTResponse(e.toString(), false, null);
        }
    }

    public static void executeJAHTTPS(final Context ctx, final Utils.Notification notifications, final String url, final Utils.Function<Void, RESTResponse> then) {
        final Activity activity = (Activity) ctx;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JuickMicroBlog.withUserId(activity, new Utils.Function<Void, Pair<Integer, String>>() {
                    @Override
                    public Void apply(final Pair<Integer, String> integerStringPair) {
                        new Thread("JAHTTPS API fetch") {
                            @Override
                            public void run() {
                                String pass = JuickAPIAuthorizer.getPassword(ctx);
                                if (pass != null) {
                                    final String password = URLEncoder.encode(pass);
                                    final RESTResponse response = Utils.getJSON(ctx, url + "&login=" + URLEncoder.encode(integerStringPair.second)
                                            + "&password=" + password, notifications);
                                    then.apply(response);
                                } else {
                                    then.apply(new RESTResponse("Not authorized (empty password)", false, null));
                                }
                            }
                        }.start();
                        return null;
                    }
                });
            }
        });
    }
}
