/*
 * Juick
 * Copyright (C) 2008-2012, Ugnich Anton
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.juick.android;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.juick.android.juick.JuickAPIAuthorizer;
import com.juick.android.juick.JuickHttpAPI;
import com.juickadvanced.R;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 *
 * @author Ugnich Anton
 */
public class SignInActivity extends Activity implements OnClickListener {

    private EditText etNick;
    private EditText etPassword;
    private Button bSave;
    private Button bCancel;
    private Handler handlErrToast = new Handler() {

        public void handleMessage(Message msg) {
            Toast.makeText(SignInActivity.this, R.string.Unknown_nick_or_wrong_password, Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.signin);


        etNick = (EditText) findViewById(R.id.juickNick);
        etPassword = (EditText) findViewById(R.id.juickPassword);
        bSave = (Button) findViewById(R.id.buttonSave);
        bCancel = (Button) findViewById(R.id.buttonCancel);

        bSave.setOnClickListener(this);
        bCancel.setOnClickListener(this);

        if (JuickAPIAuthorizer.getJuickAccountName(this) != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setNeutralButton(R.string.OK, new android.content.DialogInterface.OnClickListener() {

                public void onClick(DialogInterface arg0, int arg1) {
                    setResult(RESULT_CANCELED);
                    SignInActivity.this.finish();
                }
            });
            builder.setMessage(R.string.Only_one_account);
            builder.show();
        }
        PackageManager pm = getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo("com.juickadvanced", 0);
            ApplicationInfo ai = pi.applicationInfo;
            if ((ai.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == ApplicationInfo.FLAG_EXTERNAL_STORAGE) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setPositiveButton("Insecure", new android.content.DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface arg0, int arg1) {
                        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        sp.edit().putBoolean("use_insecure_password_storage", true).commit();
                    }
                });
                builder.setNegativeButton("Keep asking", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        //To change body of implemented methods use File | Settings | File Templates.
                    }
                });
                builder.setMessage("You have installed program to SD Card. This disables secure storage for saving login/password. Press 'Insecure' to allow Juick Advanced save login/password in its private application data instead. Otherwise it will keep asking.");
                builder.show();
            }
        } catch (PackageManager.NameNotFoundException e) {
            // do something
        }
    }

    public void onClick(View view) {
        if (view == bCancel) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final String nick = etNick.getText().toString();
        final String password = etPassword.getText().toString();

        if (nick.length() == 0 || password.length() == 0) {
            Toast.makeText(this, R.string.Enter_nick_and_password, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.Please_wait___, Toast.LENGTH_SHORT).show();

        Thread thr = new Thread(new Runnable() {

            public void run() {
                int status = 0;
                try {
                    String authStr = nick + ":" + password;
                    final String basicAuth = "Basic " + Base64.encodeToString(authStr.getBytes(), Base64.NO_WRAP);
                    Utils.verboseDebugString(SignInActivity.this, "Authorization: " + basicAuth);
                    URL apiUrl = new URL(JuickHttpAPI.getAPIURL() + "post");
                    HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setUseCaches(false);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Authorization", basicAuth);
                    conn.connect();
                    OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());
                    wr.write("body=PING");
                    wr.close();
                    status = conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) {
                    Utils.verboseDebugString(SignInActivity.this, e.toString());
                    Log.e("checkingNickPassw", e.toString());
                }
                if (status == 200) {
                    Account account = new Account(nick, getString(R.string.com_juick));
                    AccountManager am = AccountManager.get(SignInActivity.this);
                    boolean accountCreated = am.addAccountExplicitly(account, password, null);

                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    if (sp.getBoolean("use_insecure_password_storage", false)) {
                        sp.edit().putString("juick_account_name", nick).putString("juick_account_password", password).commit();
                    }

                    Bundle extras = getIntent().getExtras();
                    if (extras != null && accountCreated) {
                        AccountAuthenticatorResponse response = extras.getParcelable(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
                        Bundle result = new Bundle();
                        result.putString(AccountManager.KEY_ACCOUNT_NAME, nick);
                        result.putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.com_juick));
                        response.onResult(result);
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SignInActivity.this.finish();
                            startActivity(new Intent(SignInActivity.this, MainActivity.class));
                        }
                    });
                } else {
                    final int finalStatus = status;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Utils.verboseDebugString(SignInActivity.this, "auth: HTTP status: " + finalStatus);
                            handlErrToast.sendEmptyMessage(0);
                        }
                    });
                }
            }
        });
        thr.start();
    }
}
