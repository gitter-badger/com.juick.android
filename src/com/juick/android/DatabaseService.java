package com.juick.android;

import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.juick.android.api.JuickMessage;

import java.io.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;


/**
 * Created with IntelliJ IDEA.
 * User: san
 * Date: 9/3/12
 * Time: 2:39 PM
 * To change this template use File | Settings | File Templates.
 */
public class DatabaseService extends Service {

    Handler handler;
    SharedPreferences sp;

    public void saveMessage(final JuickMessage messag) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    Gson gson = new Gson();
                    final String value = gson.toJson(messag);
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("msgid", messag.MID);
                        cv.put("tm", messag.Timestamp.getTime());
                        cv.put("save_date", System.currentTimeMillis());
                        cv.put("body", compressGZIP(value));
                        db.insert("saved_message", null, cv);   // failed uniq constraint is handled here.
                        db.setTransactionSuccessful();
                    } catch (Exception e) {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                    return Boolean.TRUE;
                }
            });
            writeJobs.notify();
        }
    }

    public byte[] getStoredUserpic(int uid) {
        try {
            Cursor cursor = db.rawQuery("select * from userpic where uid=?", new String[]{"" + uid});
            try {
                boolean exists = cursor.moveToFirst();
                if (!exists) return null;
                return cursor.getBlob(cursor.getColumnIndex("body"));
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            return null;
        }
    }

    public void storeUserpic(final int uid, final byte[] body) {
        writeJobs.add(new Utils.Function<Boolean, Void>() {
            @Override
            public Boolean apply(Void aVoid) {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("uid", uid);
                    cv.put("body", body);
                    cv.put("save_date", System.currentTimeMillis());
                    db.insert("userpic", null, cv);
                } catch (Exception e) {
                    System.out.println("oh");
                    // duplicate key
                }
                db.setTransactionSuccessful();
                return true;
            }
        });
    }

    public void runGenericWriteJob(final Utils.Function<Boolean, DatabaseService> job) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    return job.apply(DatabaseService.this);
                }
            });
        }
    }

    public void unsaveMessage(final JuickMessage message) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    try {
                        db.execSQL("delete from saved_message where msgid=?", new Object[]{message.MID});
                        db.setTransactionSuccessful();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return Boolean.TRUE;
                }
            });
            writeJobs.notify();
        }
    }



    public ArrayList<JuickMessage> getSavedMessages(long afterSavedDate) {
        Cursor cursor = db.rawQuery("select * from saved_message where save_date < ? order by save_date desc limit 20", new String[]{"" + afterSavedDate});
        ArrayList<JuickMessage> retval = new ArrayList<JuickMessage>();
        cursor.moveToFirst();
        int blobIndex = cursor.getColumnIndex("body");
        int saveDateIndex = cursor.getColumnIndex("save_date");
        while(!cursor.isAfterLast()) {
            byte[] blob = cursor.getBlob(blobIndex);
            String str = decompressGZIP(blob);
            JuickMessage mesg = new Gson().fromJson(str, JuickMessage.class);
            if (mesg != null) {
                mesg.User.UName = mesg.User.UName.trim();   // bug i am lazy to hunt on (CR unneeded in json)
                mesg.messageSaveDate = cursor.getLong(saveDateIndex);
                retval.add(mesg);
            }
            cursor.moveToNext();
        }
        cursor.close();
        return retval;

    }

    public void reportFeature(String feature_name, String feature_value) {
        ContentValues cv = new ContentValues();
        cv.put("feature_name", feature_name);
        cv.put("feature_value", feature_value);
        db.insert("feature_usage", null, cv);
        db.setTransactionSuccessful();
    }

    public void cleanupUsageData() {
        db.beginTransaction();
        db.delete("feature_usage", "1=1", new String[]{});
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    public static void rememberVisited(JuickMessage message) {
        //To change body of created methods use File | Settings | File Templates.
    }

    public void storeThread(final int mid, final ArrayList<String> raw) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    if (raw.size() == 0) return true;        // broken?
                    Cursor cursor = db.rawQuery("select * from msg where mid=?", new String[]{"" + mid});
                    boolean exists = cursor.moveToFirst();
                    int nreplies = exists ? cursor.getInt(cursor.getColumnIndex("nreplies")) : 0;
                    cursor.close();
                    try {
                        insertOrUpdateThread(exists, raw, mid);
                        db.setTransactionSuccessful();
                        return true;
                    } catch (IOException e) {
                        Log.e("com.juickadvanced", "while storeThread", e);
                        // failure
                    }
                    return false;
                }
            });
            writeJobs.notify();
        }
        //To change body of created methods use File | Settings | File Templates.
    }

    private void insertOrUpdateThread(boolean exists, ArrayList<String> raw, int mid) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(raw);
        oos.flush();
        byte[] blob = compressGZIPArr(baos.toByteArray());
        ContentValues cv = new ContentValues();
        cv.put("body", blob);
        cv.put("nreplies", raw.size());
        cv.put("save_date", System.currentTimeMillis());
        if (exists) {
            db.update("msg", cv, "mid=?", new String[]{""+mid});
        } else {
            cv.put("mid", mid);
            db.insert("msg", "", cv);
        }
    }

    public void appendToThread(final int mid, final ArrayList<String> raw) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    ArrayList<String> storedThread = getStoredThread(mid);
                    if (storedThread == null) return true;
                    storedThread.addAll(raw);
                    try {
                        insertOrUpdateThread(true, storedThread, mid);
                    } catch (IOException e) {
                        // bad luck
                        return true;
                    }
                    db.setTransactionSuccessful();
                    return true;
                }
            });
            writeJobs.notify();
        }
        //To change body of created methods use File | Settings | File Templates.
    }


    public ArrayList<String> getStoredThread(int mid) {
        Cursor cursor = db.rawQuery("select * from msg where mid=?", new String[]{"" + mid});
        try {
            cursor.moveToFirst();
            int blobIndex = cursor.getColumnIndex("body");
            if(!cursor.isAfterLast()) {
                byte[] blob = cursor.getBlob(blobIndex);
                blob = decompressGZIPArr(blob);
                try {
                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(blob));
                    try {
                        return (ArrayList<String>)ois.readObject();
                    } finally {
                        ois.close();
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    public static class DB extends SQLiteOpenHelper {

        public final static int CURRENT_VERSION = 8;

        public DB(Context context) {
            super(context, "messages_db", null, CURRENT_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase sqLiteDatabase) {
            sqLiteDatabase.execSQL("create table message(msgid integer not null primary key, tm integer not null, prevmsgid integer not null, nextmsgid integer not null, body blob not null)");
            sqLiteDatabase.execSQL("create table message_reply(msgid integer not null, rid integer not null, body blob not null)");
            sqLiteDatabase.execSQL("create table message_read(msgid integer not null primary key, tm integer not null, nreplies integer not null)");
            sqLiteDatabase.execSQL("create index if not exists ix_message_date on message (tm)");
            sqLiteDatabase.execSQL("create index if not exists ix_message_prev on message (prevmsgid)");
            sqLiteDatabase.execSQL("create index if not exists ix_message_next on message (nextmsgid)");
            onUpgrade(sqLiteDatabase, 1, CURRENT_VERSION);
        }

        @Override
        public void onUpgrade(SQLiteDatabase sqLiteDatabase, int from, int to) {
            if (from == 1) {
                from++;
                //
            }
            if (from == 2) {
                sqLiteDatabase.execSQL("create table saved_message(msgid integer not null primary key, tm integer not null, body blob not null, save_date integer not null)");
                sqLiteDatabase.execSQL("create index if not exists ix_savedmessage_savedate on saved_message (save_date)");
                from++;
            }
            if (from == 3) {
                sqLiteDatabase.execSQL("alter table message_read add column message_date integer");
                sqLiteDatabase.execSQL("update message_read set message_date=tm");
                from++;
            }
            if (from == 4) {
                sqLiteDatabase.execSQL("create table feature_usage(feature_name text not null, feature_value text not null)");
                from++;
            }
            if (from == 5) {
                sqLiteDatabase.execSQL("create table last_visited_threads(msgid integer not null primary key, visit_date integer not null)");
                from++;
            }
            if (from == 6) {
                try {
                    sqLiteDatabase.execSQL("drop table message");
                    sqLiteDatabase.execSQL("drop table message_reply");
                } catch (SQLException e) {
                    //  no luck
                }
                // msg.blob contains serialized ArrayList<String> with messages in Strings
                // to speed up append
                sqLiteDatabase.execSQL("create table msg(mid integer, save_date integer not null, nreplies integer, body blob not null)");
                sqLiteDatabase.execSQL("create index ixmsg_mid  on msg(mid)");
                sqLiteDatabase.execSQL("create index ixmsg_savedate on msg(save_date)");
                from++;
            }
            if (from == 7) {
                sqLiteDatabase.execSQL("create table userpic(uid integer, save_date integer not null, body blob not null)");
                sqLiteDatabase.execSQL("create unique index userpic_uid  on userpic(uid)");
                sqLiteDatabase.execSQL("create index userpic_savedate on userpic(save_date)");
                from++;
            }
        }


    }

    DB database;

    private final IBinder mBinder = new Utils.ServiceGetter.LocalBinder<DatabaseService>(this);

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static class ReadMarker {
        int mid;
        long messageDate;
        int nreplies;

        ReadMarker(int mid, int nreplies, long messageDate) {
            this.mid = mid;
            this.nreplies = nreplies;
            this.messageDate = messageDate;
        }
    }

    public ArrayList<Utils.Function<Boolean, Void>> writeJobs = new ArrayList<Utils.Function<Boolean,Void>>();
    Thread writerThread;
    static SQLiteDatabase db;

    public static class MessageReadStatus {
        public int messageId;
        public boolean read;
        public int nreplies;
    }

    @Override
    public void onCreate() {
        synchronized (DatabaseService.class) {
            super.onCreate();
            sp = PreferenceManager.getDefaultSharedPreferences(this);
            handler = new Handler();
            database = new DB(this);
            if (db == null)
                db = database.getWritableDatabase();
            writerThread = new WriterThread();
            writerThread.start();
            synchronized (writeJobs) {
                writeJobs.add(new Utils.Function<Boolean, Void>() {
                    @Override
                    public Boolean apply(Void aVoid) {
                        int messageDBperiod = 30;
                        try {
                            messageDBperiod = Integer.parseInt(sp.getString("messageDBperiod","30"));
                        } catch (Exception e) {
                            //
                        }
                        String oldDate = ""+(System.currentTimeMillis() - messageDBperiod * 24 * 60 * 60 * 1000L);
                        db.delete("msg","save_date < ?",new String[] {oldDate});
                        db.setTransactionSuccessful();
                        return Boolean.TRUE;
                    }
                });
                writeJobs.notify();
            }
        }
    }

    @Override
    public void onDestroy() {
        synchronized (writeJobs) {
            writerThread.interrupt();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public void storeMessage(final JuickMessage parsed, final String json) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    if (parsed.RID > 0) {
                        Cursor cursor = db.rawQuery("select * from message_reply where msgid=? and rid=?", new String[]{"" + parsed.MID, ""+parsed.RID});
                        if (cursor.getCount() == 0) {
                            db.execSQL("insert into message_reply (msgid, rid, body) values(?,?,?)", new Object[] {parsed.MID, parsed.RID, compressGZIP(json)});
                        }
                        cursor.close();
                    } else {
                        Cursor cursor = db.rawQuery("select * from message where msgid=?", new String[]{"" + parsed.MID});
                        int msgCount = cursor.getCount();
                        cursor.close();
                        if (msgCount == 0) {
                            ContentValues cv = new ContentValues();
                            cv.put("msgid", parsed.MID);
                            cv.put("tm", parsed.Timestamp.getTime());
                            cv.put("prevmsgid", -1);
                            cv.put("nextmsgid", -1);
                            cv.put("body", compressGZIP(json));
                            if (-1 == db.insert("message", null, cv)) {
                                throw new SQLException("Insert into table MESSAGE filed");
                            }
                        }
                    }
                    db.setTransactionSuccessful();
                    return true;
                }
            });
            writeJobs.notify();
        }
    }

    private byte[] compressGZIP(String json) {
        try {
            byte[] bytes = json.getBytes();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzos = new GZIPOutputStream(baos);
            gzos.write(bytes);
            gzos.finish();
            gzos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] compressGZIPArr(byte[] bytes) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzos = new GZIPOutputStream(baos);
            gzos.write(bytes);
            gzos.finish();
            gzos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String decompressGZIP(byte[] gzipped) {
        try {
            GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(gzipped));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] arr = new byte[1024];
            while(true) {
                int rd = gzis.read(arr);
                if (rd < 1) break;
                baos.write(arr, 0, rd);
            }
            return baos.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] decompressGZIPArr(byte[] gzipped) {
        try {
            GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(gzipped));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] arr = new byte[1024];
            while(true) {
                int rd = gzis.read(arr);
                if (rd < 1) break;
                baos.write(arr, 0, rd);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    ArrayList<MessageReadStatus> cachedMRS = new ArrayList<MessageReadStatus>();

    public void getMessageReadStatus(final int messageId, final Utils.Function<Void,MessageReadStatus> callback) {
        synchronized (cachedMRS) {
            for (MessageReadStatus messageReadStatus : cachedMRS) {
                if (messageReadStatus.messageId == messageId) {
                    callback.apply(messageReadStatus);
                    return;
                }
            }
        }
        new Thread("getMessageReadStatus: "+messageId) {
            @Override
            public void run() {
                MessageReadStatus mrs = getMessageReadStatus0(messageId);
                synchronized (cachedMRS) {
                    if (cachedMRS.size() > 10) {
                        cachedMRS.remove(0);
                    }
                    cachedMRS.add(mrs);
                }
                callback.apply(mrs);
                super.run();    //To change body of overridden methods use File | Settings | File Templates.
            }
        }.start();
    }

    private MessageReadStatus getMessageReadStatus0(int messageId) {
        Cursor cursor = db.rawQuery("select * from message_read where msgid=?", new String[]{"" + messageId});
        MessageReadStatus mrs = new MessageReadStatus();
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            mrs.read = true;
            int nreplies = cursor.getInt(cursor.getColumnIndex("nreplies"));
            mrs.nreplies = nreplies;
        }
        cursor.close();
        mrs.messageId = messageId;
        return mrs;
    }

    private long getMessageDate(int messageId) {
        Cursor cursor = db.rawQuery("select message_date from message_read where msgid=?", new String[]{"" + messageId});
        if (cursor.moveToFirst()) {
            long tm = cursor.getLong(cursor.getColumnIndex("message_date"));
            cursor.close();
            return tm;
        } else {
            cursor.close();
            return -1;
        }
    }

    long lastDBReport = 0;
    public void markAsRead(final ReadMarker marker) {
        synchronized (writeJobs) {
            writeJobs.add(new Utils.Function<Boolean, Void>() {
                @Override
                public Boolean apply(Void aVoid) {
                    synchronized (cachedMRS) {
                        for (MessageReadStatus messageReadStatus : cachedMRS) {
                            if (messageReadStatus.messageId == marker.mid) {
                                messageReadStatus.read = true;
                                break;
                            }
                        }
                    }
                    ReadMarker readMarker = marker;
                    Cursor cursor = db.rawQuery("select * from message_read where msgid=?", new String[]{"" + readMarker.mid});
                    if (cursor.getCount() == 0) {
                        ContentValues contentValues = new ContentValues();
                        contentValues.put("msgid", readMarker.mid);
                        contentValues.put("tm", System.currentTimeMillis());
                        contentValues.put("nreplies", readMarker.nreplies);
                        contentValues.put("message_date", readMarker.messageDate);
                        if (-1 == db.insert("message_read", null, contentValues)) {
                            throw new SQLException("Insert into message_read failed");
                        }
                    } else {
                        cursor.moveToFirst();
                        int oldNreplies = cursor.getInt(cursor.getColumnIndex("nreplies"));
                        if (oldNreplies != readMarker.nreplies) {
                            db.execSQL("update message_read set nreplies=? where msgid=?",
                                    new Object[]{readMarker.nreplies, readMarker.mid});
                        }
                    }
                    db.setTransactionSuccessful();
                    return true;
                }
            });
            writeJobs.notify();
        }
    }



    private class WriterThread extends Thread {
        @Override
        public void run() {
            while (true) {
                ArrayList<Utils.Function<Boolean, Void>> jobs = new ArrayList<Utils.Function<Boolean, Void>>();
                synchronized (writeJobs) {
                    try {
                        if (writeJobs.size() == 0)
                            writeJobs.wait();
                        jobs.addAll(writeJobs);
                        writeJobs.clear();
                    } catch (InterruptedException e) {
                        writeJobs = null;
                        return;
                    }
                }
                for (Utils.Function<Boolean, Void> job : jobs) {
                    try {
                        db.beginTransaction();
                        if (!job.apply(null)) {
                            throw new SQLException("Job failed");
                        }
                        db.endTransaction();
                    } catch (final IllegalStateException e) {
                        // database closed
                        // bad luck.
                        break;
                    } catch (final SQLException e) {
                        synchronized (writeJobs) {
                            writeJobs.add(job);
                        }
                        reportDBError("Saving read jobs: " + e.toString());
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                        }
                    }
                }
            }
        }
    }

    public static class Period implements Serializable {
        boolean read;
        Date startDate;
        Date endDate;

        // for read messages
        public int startMid;
        int endMid;
        int numberOfMessages;

        // for unread
        int beforeMid;

        @Override
        public String toString() {
            return "Period{" +
                    "startDate=" + startDate +
                    ", endDate=" + endDate +
                    ", read=" + read +
                    '}';
        }
    }

    public ArrayList<Period> getPeriods(int days) {
        ArrayList<Period> retval = new ArrayList<Period>();
        Cursor cursor = db.rawQuery("select min(msgid), max(msgid) from message_read where tm > ?", new String[]{"" + (System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L)});
        cursor.moveToFirst();
        if (cursor.isNull(1)) {
            cursor.close();
            return retval;
        }
        int bottomMsgid = cursor.getInt(0);
        int newestMsgid = cursor.getInt(1);
        cursor.close();
        cursor = db.rawQuery("select * from message_read where msgid > ? order by msgid desc", new String[]{"" + bottomMsgid});
        cursor.moveToFirst();
        int msgidIndex = cursor.getColumnIndex("msgid");
        int messageDateIndex = cursor.getColumnIndex("message_date");
        int savedMsgid = newestMsgid;
        long savedMsgDate = getMessageDate(savedMsgid);
        while(!cursor.isAfterLast()) {
            int thisMid = cursor.getInt(msgidIndex);
            long thisMessageDate = cursor.getLong(messageDateIndex);
            if (thisMessageDate > 200) {    // bug hider :-E
                if (savedMsgid != -1 && Math.abs(thisMid - savedMsgid) > 50) {   // UNREAD HOLE
                    Period period = new Period();
                    period.startMid = savedMsgid-1;
                    period.beforeMid = savedMsgid-1;
                    period.startDate = new Date(savedMsgDate);
                    period.endMid =thisMid+1;
                    period.endDate = new Date(thisMessageDate);
                    period.read = false;
                    retval.add(period);
                }
                savedMsgid = thisMid;
                savedMsgDate = thisMessageDate;
            }
            cursor.moveToNext();
        }
        // coalesce periods
        for (int i = 0; i < retval.size() - 1; i++) {
            Period curr = retval.get(i);
            Period next = retval.get(i+1);
            if (Math.abs(next.startMid - curr.endMid) < 6) { // removing single reads
                curr.endMid = next.endMid;
                curr.endDate = next.endDate;
                retval.remove(i+1);
                i--;
            }
        }
        cursor.close();
        return retval;
    }

    public final static long DBREPORT_PERIOD_MSEC = 2 * 60 * 1000L;     // 2 minutes
    private void reportDBError(final String errmsg) {
        if (System.currentTimeMillis() - lastDBReport < DBREPORT_PERIOD_MSEC) return;
        lastDBReport = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(DatabaseService.this, errmsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public JsonObject prepareUsageReportValue() {
        JsonObject jo = new JsonObject();
        copyBoolean(jo, sp, "useXMPP", false);
        copyBoolean(jo, sp, "useXMPPOnlyForBL", false);
        copyBoolean(jo, sp, "persistLastMessagesPosition", false);
        copyBoolean(jo, sp, "lastReadMessages", false);
        copyBoolean(jo, sp, "showNumbers", false);
        copyBoolean(jo, sp, "showUserpics", true);
        copyBoolean(jo, sp, "enableMessageDB", false);
        copyBoolean(jo, sp, "confirmActions", true);
        copyBoolean(jo, sp, "enableScaleByGesture", true);
        copyBoolean(jo, sp, "compressedMenu", false);
        copyBoolean(jo, sp, "singleLineMenu", false);
        copyBoolean(jo, sp, "prefetchMessages", false);
        copyBoolean(jo, sp, "dialogMessageMenu", false);
        copyBoolean(jo, sp, "web_for_subscriptions", false);
        copyBoolean(jo, sp, "web_for_myblog", false);
        copyString(jo, sp, "messagesFontScale", "1.0");
        copyInteger(jo, sp, "Colors.COMMON_BACKGROUND", -1);
        copyString(jo, sp, "locationAccuracy", "ACCURACY_FINE");
        copyString(jo, sp, "menuFontScale", "1.0");
        copyString(jo, sp, "useBackupServer", "-1");
        copyString(jo, sp, "juickBotOn", "skip");
        copyString(jo, sp, "image.loadMode", "off");
        copyString(jo, sp, "image.height_percent", "0.3");
        copyBoolean(jo, sp, "ringtone_enabled", true);
        copyBoolean(jo, sp, "vibration_enabled", true);
        copyBoolean(jo, sp, "led_enabled", true);
        copyBoolean(jo, sp, "current_vibration_enabled", true);
        copyBoolean(jo, sp, "image.indirect", true);

        copyBoolean(jo, sp, "msrcTopMessages", true);
        copyBoolean(jo, sp, "msrcWithPhotos", true);
        copyBoolean(jo, sp, "msrcMyBlog", false);
        copyBoolean(jo, sp, "msrcSrachiki", false);
        copyBoolean(jo, sp, "msrcUnread", false);
        copyBoolean(jo, sp, "msrcSaved", false);
        copyBoolean(jo, sp, "msrcPrivate", false);
        copyBoolean(jo, sp, "msrcDiscuss", false);

        jo.addProperty("manufacturer", Build.MANUFACTURER);
        jo.addProperty("model", Build.MODEL);
        jo.addProperty("brand", Build.BRAND);
        jo.addProperty("display", Build.DISPLAY);
        jo.addProperty("display_width", MainActivity.displayWidth);
        jo.addProperty("display_height", MainActivity.displayHeight);
        String uniqueId = getUniqueInstallationId();
        jo.addProperty("device_install_id", uniqueId);
        try {
            jo.addProperty("ja_version", ""+getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0).versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            jo.addProperty("ja_version", "unknown");
        }

        Cursor cursor = db.rawQuery("select feature_name, sum(feature_value) from feature_usage group by feature_name", new String[]{});
        while(cursor.moveToNext()) {
            String name = cursor.getString(0);
            String value = cursor.getString(1);
            jo.addProperty(name, value);
        }
        cursor.close();
        return jo;
    }

    public String getUniqueInstallationId() {
        String uniqueId = "";
        WifiManager wifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            String wifiMac = wifiManager.getConnectionInfo().getMacAddress();
            uniqueId += wifiMac;
        }
        String androidID = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidID != null)
            uniqueId += androidID;
        if (uniqueId.length() == 0) {
            uniqueId = "UNKNOWN_ID";
        } else {
            uniqueId = Utils.getMD5DigestForString(uniqueId);
        }
        uniqueId += "__";


        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(getPackageName(), 0);
            String appFile = appInfo.sourceDir;
            long installed = new File(appFile).lastModified(); //Epoch Time
            uniqueId += ""+installed;
        } catch (PackageManager.NameNotFoundException e) {
        }
        return uniqueId;
    }

    private void copyBoolean(JsonObject jo, SharedPreferences sp, String prefname, boolean dflt) {
        boolean value = sp.getBoolean(prefname, dflt);
        jo.addProperty(prefname.replace('.','_'), value);
    }

    private void copyString(JsonObject jo, SharedPreferences sp, String prefname, String dflt) {
        String value = sp.getString(prefname, dflt);
        jo.addProperty(prefname.replace('.','_'), value);
    }

    private void copyInteger(JsonObject jo, SharedPreferences sp, String prefname, int dflt) {
        int value = sp.getInt(prefname, dflt);
        jo.addProperty(prefname.replace('.','_'), ""+value);
    }


}
