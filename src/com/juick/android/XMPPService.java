package com.juick.android;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;
import de.quist.app.errorreporter.ExceptionReporter;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

import java.io.*;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

/**
 */
public class XMPPService extends Service {

    XMPPConnection connection;
    Handler handler;
    Chat juickChat;
    ArrayList<Utils.Function<Void,Message>> messageReceivers = new ArrayList<Utils.Function<Void, Message>>();
    public static final String ACTION_MESSAGE_RECEIVED = "com.juick.android.action.ACTION_MESSAGE_RECEIVED";
    private final IBinder mBinder = new Utils.ServiceGetter.LocalBinder<XMPPService>(this);
    int reconnectDelay = 10000;
    int messagesReceived = 0;
    public boolean botOnline;
    static HashMap<Integer, JuickIncomingMessage> cachedTopicStarters = new HashMap<Integer, XMPPService.JuickIncomingMessage>();



    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacksAndMessages(null);
        if (intent != null && intent.getBooleanExtra("terminate", false)) {
            String message = intent.getStringExtra("terminateMessage");
            if (message == null) message = "user terminated";
            cleanup(message);
            stopSelf();
        } else {
            startup();
        }
        return super.onStartCommand(intent, flags, startId);
    }


    Thread currentThread;


    String JUICK_ID="juick@juick.com/Juick";
    Exception lastException;

    public void startup() {
        lastException = null;
        botOnline = false;
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useXMPP = sp.getBoolean("useXMPP", false);
        if (useXMPP && !(connection != null && connection.isConnected())) {
            final String username = sp.getString("xmpp_username", "");
            final String password = sp.getString("xmpp_password", "");
            final String resource = sp.getString("xmpp_resource","");
            String server = sp.getString("xmpp_server","");
            if (server.equals("gmail.com")) server = "talk.google.com";
            String port = sp.getString("xmpp_port","5222");
            String priority = sp.getString("xmpp_priority","55");
            final boolean secure = sp.getBoolean("xmpp_force_encryption", false);
            int iPort = 0;
            int iPriority = 0;
            try {iPort = Integer.parseInt(port); } catch (NumberFormatException e) {
                Toast.makeText(XMPPService.this, "XMPP: Invalid port. ", Toast.LENGTH_SHORT).show();
                return;
            }
            try {iPriority = Integer.parseInt(priority); } catch (NumberFormatException e) {
                Toast.makeText(XMPPService.this, "XMPP: Invalid priority. ", Toast.LENGTH_SHORT).show();
                return;
            }
            final int finalIPort = iPort;
            final int finalIPriority = iPriority;
            final String finalServer = server;
            (currentThread = new Thread() {
                @Override
                public void run() {
                    try {
                        String serviceName = finalServer;
                        if (finalServer.equals("talk.google.com"))
                            serviceName = "gmail.com";
                        ConnectionConfiguration configuration = new ConnectionConfiguration(finalServer, finalIPort, serviceName);
                        configuration.setSecurityMode(secure ? ConnectionConfiguration.SecurityMode.required : ConnectionConfiguration.SecurityMode.enabled);
                        configuration.setReconnectionAllowed(true);
                        SASLAuthentication.supportSASLMechanism("PLAIN", 0);
                        //configuration.setSASLAuthenticationEnabled(secure);
                        //configuration.setCompressionEnabled(true);
                        int delay = 1000;
                        while(true) {
                            if (currentThread != Thread.currentThread()) return;        // we are abandoned!
                            if (connection != null && connection.isConnected()) break;
                            connection = new XMPPConnection(configuration);
                            connection.connect();
                            if (connection.isConnected()) break;
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException e) {
                                cleanup("connect interrupted");
                                return;
                            }
                            delay *= 2;
                            if (delay > 15*60*1000) {
                                delay = 15*60*1000;
                            }
                        }
                        reconnectDelay = 10000;     // reset value
                        connection.addConnectionListener(new ConnectionListener() {
                            @Override
                            public void connectionClosed() {
                                System.out.println();
                                //To change body of implemented methods use File | Settings | File Templates.
                            }

                            @Override
                            public void connectionClosedOnError(Exception e) {
                                System.out.println();
                                //To change body of implemented methods use File | Settings | File Templates.
                            }

                            @Override
                            public void reconnectingIn(int seconds) {
                                System.out.println();
                            }

                            @Override
                            public void reconnectionSuccessful() {
                                System.out.println();
                            }

                            @Override
                            public void reconnectionFailed(Exception e) {
                                cleanup("failed to reconnect, juick will retry later");
                                scheduleReconnect();
                            }
                        });
                        String fullUsername = username;
                        if (fullUsername.indexOf("@") != -1)
                            fullUsername = fullUsername.substring(0, fullUsername.indexOf("@"));
                        connection.login(fullUsername, password, resource);
                    } catch (final IllegalStateException e) {
                        cleanup(null);
                        scheduleReconnect();
                        return;
                    } catch (final XMPPException e) {
                        if (e.getWrappedThrowable() instanceof SocketException) {
                            scheduleReconnect();
                        }
                        lastException = e;
                        if (currentThread() == currentThread) {
                            String message = e.toString();
                            if (message.toLowerCase().indexOf("auth") >= 0)
                                message = "!"+message;
                            cleanup(message);
                            connection = null;
                            currentThread = null;
                        }
                        return;
                    }
                    if (currentThread() != currentThread) return;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (verboseXMPP())
                                Toast.makeText(XMPPService.this, "XMPP connect OK", Toast.LENGTH_SHORT).show();
                        }
                    });
                    Roster roster = connection.getRoster();
                    juickChat = connection.getChatManager().createChat(JUICK_ID, new MessageListener() {
                        @Override
                        public void processMessage(Chat chat, Message message) {
                            for (Utils.Function<Void, Message> messageReceiver : (Iterable<? extends Utils.Function<Void,Message>>) messageReceivers.clone()) {
                                messageReceiver.apply(message);
                            }
                        }
                    });
                    connection.addPacketListener(packetListener, new MessageTypeFilter(Message.Type.chat));
                    connection.addPacketListener(packetListener2, new MessageTypeFilter(Message.Type.normal));
                    try {
                        connection.sendPacket(new Presence(Presence.Type.available, "android juick client here", finalIPriority, Presence.Mode.available));
                    } catch (Exception e) {
                        cleanup("error while sending presence");
                        scheduleReconnect();
                        return;
                    }
                    messageReceivers.add(new Utils.Function<Void, Message>() {
                        @Override
                        public Void apply(Message message) {
                            // general juick message receiver
                            if (JUICK_ID.equals(message.getFrom())) {
                                messagesReceived++;
                                handleJuickMessage(message);
                            }
                            return null;
                        }
                    });
                    roster.addRosterListener(new RosterListener() {
                        @Override
                        public void entriesAdded(Collection<String> addresses) {
                            System.out.println();
                        }

                        @Override
                        public void entriesUpdated(Collection<String> addresses) {
                            System.out.println();
                        }

                        @Override
                        public void entriesDeleted(Collection<String> addresses) {
                            //To change body of implemented methods use File | Settings | File Templates.
                        }

                        @Override
                        public void presenceChanged(final Presence presence) {
                            if (presence.getFrom().equals(JUICK_ID)) {
                                botOnline = presence.isAvailable();
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (verboseXMPP()) {
                                            if (botOnline) {
                                                Toast.makeText(XMPPService.this, "juick bot online", Toast.LENGTH_SHORT).show();
                                            } else {
                                                Toast.makeText(XMPPService.this, "JUICK BOT OFFLINE", Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    }
                                });
                                if (botOnline) {
                                    sendJuickMessage("ON");
                                }
                            }
                        }
                    });
                }

            }).start();
        }

    }

    private boolean verboseXMPP() {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        return sp.getBoolean("xmpp_verbose", false);
    }

    private void scheduleReconnect() {
        reconnectDelay *= 2;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            final Connection retryConnection = connection;
            @Override
            public void run() {
                startup();
            }
        }, reconnectDelay);
    }

    PacketListener packetListener = new PacketListener() {
        @Override
        public void processPacket(Packet packet) {
            messagesReceived++;
            if (packet instanceof Message && !JUICK_ID.equals(packet.getFrom())) {
                handleTextMessage((Message) packet);
            }
        }
    };

    PacketListener packetListener2 = new PacketListener() {
        @Override
        public void processPacket(Packet packet) {
            messagesReceived++;
            if (packet instanceof Message && !JUICK_ID.equals(packet.getFrom())) {
                handleTextMessage((Message) packet);
            }
        }
    };

    public void removeReceivedMessages(int mid) {
        synchronized (incomingMessages) {
            Iterator<IncomingMessage> iterator = incomingMessages.iterator();
            while (iterator.hasNext()) {
                IncomingMessage next = iterator.next();
                if (next instanceof JuickIncomingMessage) {
                    JuickIncomingMessage jim = (JuickIncomingMessage)next;
                    if (jim.getPureThread() == mid) {
                        iterator.remove();
                    }
                }
            }
        }
        maybeCancelNotification();
    }

    public void maybeCancelNotification() {
        if (incomingMessages.size() == 0)
            XMPPMessageReceiver.cancelInfo(this);
        else
            XMPPMessageReceiver.updateInfo(this, incomingMessages.size(), true);
    }

    public void removeMessage(IncomingMessage incomingMessage) {
        synchronized (incomingMessage) {
            removeMessageFile(incomingMessage.id);
            incomingMessages.remove(incomingMessage);
        }
        maybeCancelNotification();
    }

    public void removeMessages(Class messageClass) {
        synchronized (incomingMessages) {
            Iterator<IncomingMessage> iterator = incomingMessages.iterator();
            while (iterator.hasNext()) {
                IncomingMessage next = iterator.next();
                if (next.getClass() == messageClass) {
                    iterator.remove();
                }
            }
        }
        maybeCancelNotification();
    }

    public void requestMessageBody(int finalTopicMessageId) {
        if (connection.isConnected()) {
            try {
                juickChat.sendMessage("#" + finalTopicMessageId);
            } catch (XMPPException e) {
                Toast.makeText(this, "requestMessageBody: "+e.toString(), Toast.LENGTH_LONG).show();
            }
        }
    }

    static int message_seq = 0;
    public static abstract class IncomingMessage implements Serializable {
        protected String from;
        String body;
        String id;

        IncomingMessage(String from, String body) {
            this.from = from;
            this.body = body;
            synchronized (IncomingMessage.class) {
                this.id = System.currentTimeMillis() + "_" + (message_seq++);
            }
        }

        public String getBody() {

            return body;
        }

        public String getFrom() {
            return from;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            IncomingMessage that = (IncomingMessage) o;

            if (id != null ? !id.equals(that.id) : that.id != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            return id != null ? id.hashCode() : 0;
        }
    }

    public static abstract class JuickIncomingMessage extends IncomingMessage {
        String messageNo;

        public JuickIncomingMessage(String from, String body, String messageNo) {
            super(from, body);
            this.messageNo = messageNo;
        }
        public String getFrom() {
            if (from.startsWith("@")) return from;
            return "@"+from;
        }
        public int getPureThread() {
            try {
                int ix = messageNo.indexOf("/");
                if (ix ==-1) return Integer.parseInt(messageNo.substring(1));
                int ix2 = messageNo.indexOf("#");   // -1 not found
                return Integer.parseInt(messageNo.substring(ix2+1, ix));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    public static class JuickPrivateIncomingMessage extends JuickIncomingMessage {
        public JuickPrivateIncomingMessage(String from, String body, String messageNo) {
            super(from, body, messageNo);
        }
    }

    public static class JuickThreadIncomingMessage extends JuickIncomingMessage {
        private String originalBody;
        private String originalFrom;

        public JuickThreadIncomingMessage(String from, String body, String messageNo) {
            super(from, body, messageNo);
        }

        public CharSequence getOriginalBody() {
            return originalBody;
        }

        public CharSequence getOriginalFrom() {
            return originalFrom;
        }

        public void setOriginalBody(String originalBody) {
            this.originalBody = originalBody;
        }

        public void setOriginalFrom(String originalFrom) {
            this.originalFrom = originalFrom;
        }
    }

    public static class JuickSubscriptionIncomingMessage extends JuickIncomingMessage {
        public JuickSubscriptionIncomingMessage(String from, String body, String messageNo) {
            super(from, body, messageNo);
        }
    }

    public static class JabberIncomingMessage extends IncomingMessage {
        JabberIncomingMessage(String from, String body) {
            super(from, body);
        }
    }

    public ArrayList<IncomingMessage> incomingMessages = new ArrayList<IncomingMessage>();

    private void handleJuickMessage(Message message) {
        IncomingMessage handled = null;
        boolean silent = false;
        synchronized (incomingMessages) {
            String[] split = message.getBody().split("\n");
            String head = split[0];
            if (head.startsWith("Reply by @")) {
                int colon = head.indexOf(":");
                String username = head.substring(10, colon);
                try {
                    String msgno = split[split.length - 1].trim().split(" ")[0].trim();
                    if (msgno.startsWith("#")) {
                        StringBuilder sb = new StringBuilder();
                        for(int i=3; i<split.length-2; i++) {
                            sb.append(split[i]);
                            sb.append("\n");
                        }
                        JuickThreadIncomingMessage threadIncomingMessage = new JuickThreadIncomingMessage(username, sb.toString(), msgno);
                        XMPPService.JuickIncomingMessage topicStarter = cachedTopicStarters.get(threadIncomingMessage.getPureThread());
                        if (topicStarter == null) {
                            topicStarter = new JuickThreadIncomingMessage("@???","","#"+threadIncomingMessage.getPureThread());    // put placeholder for details
                            cachedTopicStarters.put(threadIncomingMessage.getPureThread(), topicStarter);
                            requestMessageBody(threadIncomingMessage.getPureThread());
                        } else {
                            threadIncomingMessage.setOriginalBody(topicStarter.getBody());
                            threadIncomingMessage.setOriginalFrom(topicStarter.getFrom());
                        }
                        saveMessage(threadIncomingMessage);
                        incomingMessages.add(threadIncomingMessage);
                        handled = threadIncomingMessage;
                    }
                } catch (Exception ex) {
                    //
                }
            }
            if (head.startsWith("@") && head.contains(":")) {
                int colon = head.indexOf(":");
                if (colon != -1) {
                    String username = head.substring(0, colon);
                    if (head.indexOf("*private") != -1) {
                        if (split.length >= 7) {
                            try {
                                String msgno = split[split.length - 1].trim().split(" ")[0].trim();
                                if (msgno.startsWith("#")) {
                                    StringBuilder sb = new StringBuilder();
                                    for(int i=1; i<split.length-4; i++) {
                                        sb.append(split[i]);
                                        sb.append("\n");
                                    }
                                    if (sb.length() > 1 && sb.charAt(sb.length()-2) == '\n') {
                                        sb. setLength(sb.length()-1);       // remove last empty line

                                    }
                                    JuickPrivateIncomingMessage object = new JuickPrivateIncomingMessage(username, sb.toString(), msgno);
                                    saveMessage(object);
                                    handled = object;
                                    incomingMessages.add(object);
                                }
                            } catch (Exception ex) {
                                //
                            }
                        }
                    } else {
                        String last = split[split.length-1];
                        String[] msgNoAndURL = last.trim().split(" ");
                        if (msgNoAndURL.length >= 2 && msgNoAndURL[0].trim().startsWith("#")) {
                            String msgNo = msgNoAndURL[0].trim();
                            String url = msgNoAndURL[msgNoAndURL.length-1].trim();
                            if (url.equals("http://juick.com/"+msgNo.substring(1))) {
                                StringBuilder sb = new StringBuilder();
                                for(int i=1; i<split.length-1; i++) {
                                    sb.append(split[i]);
                                    sb.append("\n");
                                }
                                JuickSubscriptionIncomingMessage subscriptionIncomingMessage = new JuickSubscriptionIncomingMessage(username, sb.toString(), msgNo);
                                JuickIncomingMessage topicStarter = cachedTopicStarters.get(subscriptionIncomingMessage.getPureThread());
                                if (topicStarter != null && topicStarter.getBody().length() == 0) {
                                    cachedTopicStarters.put(subscriptionIncomingMessage.getPureThread(), subscriptionIncomingMessage);
                                    for (IncomingMessage incomingMessage : incomingMessages) {
                                        if (incomingMessage instanceof JuickThreadIncomingMessage && ((JuickThreadIncomingMessage) incomingMessage).getPureThread() == topicStarter.getPureThread()) {
                                            // details came!
                                            JuickThreadIncomingMessage imsg = (JuickThreadIncomingMessage) incomingMessage;
                                            imsg.setOriginalBody(subscriptionIncomingMessage.getBody());
                                            imsg.setOriginalFrom(subscriptionIncomingMessage.getFrom());
                                            silent = true;
                                        }
                                    }
                                } else {
                                    saveMessage(subscriptionIncomingMessage);
                                    incomingMessages.add(subscriptionIncomingMessage);
                                    handled = subscriptionIncomingMessage;
                                }
                            }
                        }
                    }
                }
            }
            if (message.getFrom().equals(JUICK_ID)) {
                if (message.getBody().toLowerCase().contains("delivery of messages is")) {
                    silent = true;
                }

            }
            if (handled == null && !silent) {
                JabberIncomingMessage messag = new JabberIncomingMessage(message.getFrom(), message.getBody());
                saveMessage(messag);
                incomingMessages.add(messag);
            }
        }
        if (!silent)
            sendMyBroadcast();
    }

    private void saveMessage(IncomingMessage message) {
        try {
            File xmpp_messages_v1 = getSavedMessagesDirectory();
            boolean mkdirs = xmpp_messages_v1.mkdirs();
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(xmpp_messages_v1, message.id)));
            oos.writeObject(message);
            oos.close();
        } catch (IOException e) {
            Log.e("com.juickadvanced", "saveMessage", e);
        }
    }

    private void removeMessageFile(String id) {
        File xmpp_messages_v1 = getSavedMessagesDirectory();
        File file = new File(xmpp_messages_v1, id);
        file.delete();
    }

    private File getSavedMessagesDirectory() {
        return getDir("xmpp_messages_v1", MODE_PRIVATE);
    }

    private void sendMyBroadcast() {
        Intent intent = new Intent();
        intent.setAction(ACTION_MESSAGE_RECEIVED);
        intent.putExtra("messagesCount", incomingMessages.size());
        intent.putExtra("messagesCount", incomingMessages.size());
        sendBroadcast(intent);
    }

    private void handleTextMessage(Message message) {
        synchronized (incomingMessages) {
            JabberIncomingMessage msg = new JabberIncomingMessage(message.getFrom(), message.getBody());
            saveMessage(msg);
            incomingMessages.add(msg);
        }
        sendMyBroadcast();
    }

    private void sendJuickMessage(String text) {
        try {
            juickChat.sendMessage(text);
        } catch (XMPPException e) {
            cleanup("Error sending message");
        }
    }

    public void cleanup(final String reason) {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                //
            }
            connection = null;
            botOnline = false;
            if (reason != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (verboseXMPP() || reason.startsWith("!"))
                            Toast.makeText(XMPPService.this, "XMPP Disconnected: "+reason, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    @Override
    public void onCreate() {
        ExceptionReporter.register(this);
        handler = new Handler();
        super.onCreate();
        File savedMessagesDirectory = getSavedMessagesDirectory();
        String[] list = savedMessagesDirectory.list();
        try {
            if (list != null) {
                for (String fname : list) {
                    ObjectInputStream ois = new ObjectInputStream(new FileInputStream(new File(savedMessagesDirectory, fname)));
                    IncomingMessage o = (IncomingMessage) ois.readObject();
                    ois.close();
                    incomingMessages.add(o);

                }
            }
        } catch (Exception e) {
            Log.e("com.juickadvanced", "restoreMessages", e);
        }
        if (incomingMessages.size() > 0) {
            XMPPMessageReceiver.updateInfo(this, incomingMessages.size(), true);
        }

    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();    //To change body of overridden methods use File | Settings | File Templates.
    }
}
