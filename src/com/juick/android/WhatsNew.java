package com.juick.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Toast;
import com.google.gson.JsonObject;
import com.juickadvanced.R;

/**
 * Created with IntelliJ IDEA.
 * User: san
 * Date: 9/8/12
 * Time: 4:53 PM
 * To change this template use File | Settings | File Templates.
 */
public class WhatsNew {

    public static final long REPORT_SEND_PERIOD = 2 * 24 * 60 * 60 * 1000L;


    class ReleaseFeatures {
        int textId;
        String sinceRelease;

        ReleaseFeatures(String sinceRelease, int textId) {
            this.sinceRelease = sinceRelease;
            this.textId = textId;
        }
    }

    ReleaseFeatures[] features = new ReleaseFeatures[] {
            new ReleaseFeatures("2012090502", R.string.rf_2012090502),
            new ReleaseFeatures("2012082601", R.string.rf_2012082601),
            new ReleaseFeatures("2012081901", R.string.rf_2012081901),
            new ReleaseFeatures("2012081701", R.string.rf_2012081701),
    };

    Activity context;

    public WhatsNew(Activity context) {
        this.context = context;
    }

    void maybeRunFeedbackAndMore() {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        final Runnable after = new Runnable() {
            @Override
            public void run() {
                String chosen = sp.getString("usage_statistics", "no");
                if (chosen.equals("no_hello")) {
                    // say hello
                    sp.edit().putString("usage_statistics","no").commit();
                    Utils.ServiceGetter<DatabaseService> databaseServiceServiceGetter = new Utils.ServiceGetter<DatabaseService>(context, DatabaseService.class);
                    databaseServiceServiceGetter.getService(new Utils.ServiceGetter.Receiver<DatabaseService>() {
                        @Override
                        public void withService(final DatabaseService service) {
                            final JsonObject jo = new JsonObject();
                            String uniqueInstallationId = service.getUniqueInstallationId();
                            jo.addProperty("device_install_id", uniqueInstallationId);
                            new Thread() {
                                @Override
                                public void run() {
                                    Utils.postJSONHome(service, "/usage_report_handler", jo.toString());
                                }

                            }.start();
                        }
                    });
                }
                // continue here
            }
        };
        String currentSetting = sp.getString("usage_statistics", "");
        if (currentSetting.length() == 0) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            final View stat = context.getLayoutInflater().inflate(R.layout.enable_statistics, null);

            stat.findViewById(R.id.read_privacy_policy).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPrivacyPolicy();
                }
            });
            final AlertDialog alert = builder.setTitle(context.getString(R.string.UsageStatistics))
                    .setMessage(context.getString(R.string.EnableUsageStatistics))
                    .setCancelable(false)
                    .setView(stat)
                    .create();
            stat.findViewById(R.id.ok).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    RadioButton us_send = (RadioButton)stat.findViewById(R.id.us_send);
                    RadioButton us_send_wifi = (RadioButton)stat.findViewById(R.id.us_send_wifi);
                    RadioButton us_no_hello = (RadioButton)stat.findViewById(R.id.us_no_hello);
                    RadioButton us_no = (RadioButton)stat.findViewById(R.id.us_no);
                    String option = "";
                    if (us_send.isChecked())
                        option  = "send";
                    if (us_send_wifi.isChecked())
                        option  = "send_wifi";
                    if (us_no_hello.isChecked())
                        option  = "no_hello";
                    if (us_no.isChecked())
                        option  = "no";
                    if (option.length() != 0) {
                        sp.edit().putString("usage_statistics", option).commit();
                        alert.dismiss();
                        after.run();
                    } else {
                        Toast.makeText(context, context.getString(R.string.ChooseFirstOption), Toast.LENGTH_SHORT).show();
                    }
                }
            });
            alert.show();
        } else {
            after.run();
        }
    }

    public void showPrivacyPolicy() {
        WebView wv = new WebView(context);
        Utils.setupWebView(wv, context.getString(R.string.privacy_policy));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.Privacy_Policy)
                .setView(wv)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                })
                .setCancelable(true);
        builder.show();
    }


    public void runAll() {
        Runnable after = new Runnable() {
            @Override
            public void run() {
                maybeRunFeedbackAndMore();
            }
        };
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String last_features_reported = sp.getString("last_features_reported", "");
        if (last_features_reported.length() == 0) {
            ReleaseFeatures feature = features[0];
            sp.edit().putString("last_features_reported", feature.sinceRelease).commit();
            reportFeatures(0, false, after);
            return;
        } else {
            for (int i = 0; i < features.length; i++) {
                ReleaseFeatures feature = features[i];
                if (feature.sinceRelease.compareTo(last_features_reported) > 0) {
                    sp.edit().putString("last_features_reported", feature.sinceRelease).commit();
                    reportFeatures(i, true, after);
                    return;
                }
            }
        }
        if (after != null)
            after.run();

    }

    public void reportFeatures(int sequence, boolean cycle, final Runnable notCycle) {
        WebView wv = new WebView(context);
        ReleaseFeatures feature = features[sequence];
        Utils.setupWebView(wv, context.getString(feature.textId));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.NewFeatures)
                .setView(wv)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                        if (notCycle != null)
                            notCycle.run();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        if (notCycle != null)
                            notCycle.run();
                    }
                })
                .setCancelable(true);
        if (sequence < features.length - 1 && cycle) {
            builder.setPositiveButton("Есть еще...", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    runAll();
                }
            });
        }
        builder.show();
    }

    public void report(Context context) {
        final TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        final String deviceId = telephonyManager.getDeviceId();

    }

    public static class FeatureSaver {
        public void saveFeature(DatabaseService db) {

        }
    }

    public void reportFeature(final String feature_name, final String feature_value) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String chosen = sp.getString("usage_statistics", "no");
        if (chosen.startsWith("send")) {
            reportFeature(new FeatureSaver() {
                @Override
                public void saveFeature(DatabaseService db) {
                    db.reportFeature(feature_name, feature_value);
                }
            });
        }
    }

    public void reportFeature(final FeatureSaver saver) {
        Utils.ServiceGetter<DatabaseService> databaseServiceServiceGetter = new Utils.ServiceGetter<DatabaseService>(context, DatabaseService.class);
        databaseServiceServiceGetter.getService(new Utils.ServiceGetter.Receiver<DatabaseService>() {
            @Override
            public void withService(DatabaseService service) {
                service.runGenericWriteJob(new Utils.Function<Void, DatabaseService>() {
                    @Override
                    public Void apply(DatabaseService databaseService) {
                        saver.saveFeature(databaseService);
                        return null;
                    }
                });
            }
        });
    }

    public void reportUsage() {
        final Utils.ServiceGetter<DatabaseService> databaseServiceServiceGetter = new Utils.ServiceGetter<DatabaseService>(context, DatabaseService.class);
        databaseServiceServiceGetter.getService(new Utils.ServiceGetter.Receiver<DatabaseService>() {
            @Override
            public void withService(final DatabaseService service) {
                final JsonObject jsonObject = service.prepareUsageReportValue();
                new Thread() {
                    @Override
                    public void run() {
                        String usageReport = jsonObject.toString();
                        if ("OK".equals(Utils.postJSONHome(service, "/usage_report_handler", usageReport))) {
                            databaseServiceServiceGetter.getService(new Utils.ServiceGetter.Receiver<DatabaseService>() {
                                @Override
                                public void withService(DatabaseService service) {
                                    service.cleanupUsageData();
                                    service.handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                                            sp.edit().putLong("last_usage_sent", System.currentTimeMillis()).commit();
                                            MainActivity.usageReportThread = null;
                                        }
                                    });
                                }
                            });
                        } else {
                            //error
                            service.handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    // try again in 3 hours
                                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                                    sp.edit().putLong("last_usage_sent", System.currentTimeMillis() - REPORT_SEND_PERIOD + 3 * 60 * 60 * 1000L).commit();
                                    MainActivity.usageReportThread = null;
                                }
                            });
                        }
                    }
                }.start();


            }
        });
    }

    public void showUsageReport() {
        Utils.ServiceGetter<DatabaseService> databaseServiceServiceGetter = new Utils.ServiceGetter<DatabaseService>(context, DatabaseService.class);
        databaseServiceServiceGetter.getService(new Utils.ServiceGetter.Receiver<DatabaseService>() {
            @Override
            public void withService(final DatabaseService service) {
                final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                String chosen = sp.getString("usage_statistics", "no");
                String decodedUsageStatistics = decodeUsageStatistics(chosen);
                StringBuilder sb = new StringBuilder();
                sb.append(context.getString(R.string.statistics_mode));
                sb.append(decodedUsageStatistics);
                sb.append("<br><hr>");
                JsonObject jsonObject = service.prepareUsageReportValue();
                String unsafeString = jsonObject.toString();
                String safeString = unsafeString.replace("<","&lt;");
                sb.append(safeString);
                WebView wv = new WebView(service);
                String htmlContent = sb.toString();
                Utils.setupWebView(wv, context.getString(R.string.HTMLStart) + htmlContent);

                AlertDialog.Builder builder = new AlertDialog.Builder(context)
                        .setTitle(R.string.Privacy_Policy)
                        .setView(wv)
                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.cancel();
                            }
                        })
                        .setCancelable(true);
                builder.show();
            }
        });
    }

    private String decodeUsageStatistics(String chosen) {
        if (chosen.equals("no")) return context.getString(R.string.us_no);
        if (chosen.equals("no_hello")) return context.getString(R.string.us_no_hello_done);
        if (chosen.equals("send_wifi")) return context.getString(R.string.us_send_wifi);
        if (chosen.equals("send")) return context.getString(R.string.us_send);
        return "???";
    }


}