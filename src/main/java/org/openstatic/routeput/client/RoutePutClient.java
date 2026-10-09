package org.openstatic.routeput.client;

import org.json.*;
import java.util.Vector;
import java.util.concurrent.Future;
import java.util.Collection;

import java.io.File;

import org.openstatic.routeput.BLOBManager;
import org.openstatic.routeput.BLOBFile;
import org.openstatic.routeput.RoutePutChannel;
import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutSession;
import org.openstatic.routeput.RoutePutRemoteSession;
import org.openstatic.routeput.RoutePutMessageListener;
import org.openstatic.routeput.RoutePutMain;
import org.openstatic.routeput.RoutePutPropertyChangeMessage;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class RoutePutClient implements RoutePutSession, Runnable
{
    static {
        // Never let the JVM cache a failed DNS lookup: otherwise a brief outage poisons
        // InetAddress' negative cache and every reconnect keeps throwing UnknownHostException
        // even after the network recovers. 0 = re-resolve on every attempt.
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }

    private PropertyChangeSupport propertyChangeSupport;
    private RoutePutChannel channel;
    private String websocketUri;
    private String connectionId;
    private WebSocketClient webSocketClient;
    private volatile WebSocketSession session;
    private EventsWebSocket eventsWebSocket;
    private Vector<RoutePutMessageListener> listeners;

    private volatile boolean stayConnected;
    private JSONObject properties;
    private String remoteIP;
    private boolean collector;
    private volatile Thread keepAliveThread;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    // Last time any frame arrived from the server. The keep-alive treats a socket that still
    // claims open but has gone silent past serverSilenceTimeoutMs as dead and forces a
    // reconnect. Recovers from a hiccup where TCP survives (so session.isOpen() keeps lying)
    // but the server already ping/pong-dropped us, which would otherwise leave us able to
    // send yet absent from the channel roster. 0 disables the check.
    private volatile long lastServerContactAt;
    private volatile long serverSilenceTimeoutMs = 45000l;
    // Passwords keyed by channel name; used for the initial handshake and any
    // per-channel subscribe against a password-gated channel.
    private final java.util.HashMap<String, String> channelPasswords = new java.util.HashMap<String, String>();
    // Extra channels joined via subscribe() (beyond the default channel). Replayed on
    // reconnect so a dropped-and-resumed link rejoins every channel, not just the default.
    private final java.util.Set<String> subscribedChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Serializes all outbound writes so async frames never overlap on the RemoteEndpoint.
    private static final RoutePutMessage WRITE_POISON = new RoutePutMessage();
    private final BlockingQueue<RoutePutMessage> writeQueue = new LinkedBlockingQueue<RoutePutMessage>();
    private volatile Thread writeWorker;

    public RoutePutClient(RoutePutChannel channel, String websocketUri)
    {
        this(channel, websocketUri, null);
    }

    public RoutePutClient(RoutePutChannel channel, String websocketUri, String defaultChannelPassword)
    {
        this.propertyChangeSupport = new PropertyChangeSupport(this);
        this.listeners = new Vector<RoutePutMessageListener>();
        this.channel = channel;
        this.websocketUri = websocketUri;
        this.collector = false;
        this.stayConnected = true;
        this.connectionId = RoutePutChannel.getMasterConnectionId();
        this.properties = new JSONObject();
        this.properties.put("_version", RoutePutMain.VERSION);
        // Ensure blob storage is up so incoming chunks are actually kept; no-op if a
        // RoutePutServer in the same JVM already initialized with its own settings.
        BLOBManager.initClient();
        if (channel != null && defaultChannelPassword != null && !defaultChannelPassword.isEmpty())
        {
            this.channelPasswords.put(channel.getName(), defaultChannelPassword);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() 
        { 
          public void run() 
          {
            RoutePutClient.this.stayConnected = false;
            //System.err.println("Routeput client received shutdown hook!");
            RoutePutClient.this.cleanUp();
          } 
        }); 

        this.ensureWebSocketClientStarted();

        RoutePutClient.this.eventsWebSocket = new EventsWebSocket();
    }

    // Guarantees a started WebSocketClient before a connect attempt. A stopped Jetty
    // client (after shutdown() or an internal lifecycle stop) throws "is not started"
    // on connect, so rebuild it from scratch rather than reusing torn-down internals.
    private synchronized void ensureWebSocketClientStarted()
    {
        if (this.webSocketClient != null && this.webSocketClient.isStarted())
            return;
        if (this.webSocketClient != null)
        {
            try {
                this.webSocketClient.stop();
            } catch (Exception e) {
                // ignore
            }
        }
        SslContextFactory sec = new SslContextFactory.Client();
        sec.setValidateCerts(false);
        HttpClient httpClient = new HttpClient(sec);
        WebSocketClient client = new WebSocketClient(httpClient);
        client.setMaxIdleTimeout(120000);
        try
        {
            client.start();
            this.webSocketClient = client;
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public CompletableFuture<Void> forceSendBlob(File file)
    {
        return this.forceSendBlob(file, null);
    }

    public CompletableFuture<Void> forceSendBlob(File file, RoutePutMessage request)
    {
        return this.forceSendBlob(this.getDefaultChannel(), file, request);
    }

    public CompletableFuture<Void> forceSendBlob(RoutePutChannel channel, File file, RoutePutMessage request)
    {
        return BLOBManager.forceSendBlob(this, channel, file, request);
    }

    // Convenience method to send a blob using the default channel with no request.
    public CompletableFuture<Void> sendBlob(File file)
    {
        return this.sendBlob(file, null);
    }

    // Convenience method to send a blob using the default channel.
    public CompletableFuture<Void> sendBlob(File file, RoutePutMessage request)
    {
        return this.sendBlob(this.getDefaultChannel(), file, request);
    }

    // maps to BLOBManager.sendBlob(this, channel, file, request)
    public CompletableFuture<Void> sendBlob(RoutePutChannel channel, File file, RoutePutMessage request)
    {
        return BLOBManager.sendBlob(this, channel, file, request);
    }

    public void setConnectionId(String connectionId)
    {
        this.connectionId = connectionId;
    }

    public String toString()
    {
        return this.getName();
    }

    public String getName()
    {
        String state = "disconnected";
        if (this.isConnected())
            state = "connected";
        return "RoutePutClient(" + state + ") - " + this.websocketUri;
    }

    public void setCollector(boolean v)
    {
        this.collector = v;
        if (this.isConnected()) {
            if (this.collector) {
                RoutePutMessage rpm = new RoutePutMessage();
                rpm.setRequest("becomeCollector");
                this.send(rpm);
            } else {
                RoutePutMessage rpm = new RoutePutMessage();
                rpm.setRequest("dropCollector");
                this.send(rpm);
            }
        }
    }

    public void ping() 
    {
        RoutePutMessage pingMessage = new RoutePutMessage();
        pingMessage.setType("ping");
        pingMessage.setChannel(this.getDefaultChannel());
        pingMessage.setMetaField("timestamp", System.currentTimeMillis());
        this.send(pingMessage);
    }

    public void shutdown()
    {
        this.stayConnected = false;
        this.close();
        try
        {
            this.webSocketClient.stop();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public void setAutoReconnect(boolean v) 
    {
        this.stayConnected = v;
        if (!v) {
            this.close();
        }
        this.wakeKeepAlive();
    }

    public boolean isAutoReconnect()
    {
        return this.stayConnected;
    }

    // How long the server may go silent on an otherwise-open socket before the keep-alive
    // forces a reconnect. Set <= 0 to disable. Keep it above 2x the server's pingPongSecs.
    public void setServerSilenceTimeout(long ms)
    {
        this.serverSilenceTimeoutMs = ms;
    }

    public long getServerSilenceTimeout()
    {
        return this.serverSilenceTimeoutMs;
    }

    @Override
    public String getConnectionId()
    {
        return this.connectionId;
    }

    @Override
    public RoutePutChannel getDefaultChannel()
    {
        return this.channel;
    }

    @Override
    public String getRemoteIP()
    {
        return this.remoteIP;
    }

    @Override
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.getConnectionId());
        jo.put("defaultChannel", this.getDefaultChannel());
        jo.put("properties", this.properties);
        // jo.put("_sessionListeners", this.remoteSessionListeners.size());
        return jo;
    }

    public void waitForConnection(long timeoutMs) throws InterruptedException
    {
        long startTime = System.currentTimeMillis();
        while (!this.isConnected() && (System.currentTimeMillis() - startTime) < timeoutMs)
        {
            Thread.sleep(100);
        }
    }

    @Override
    public boolean isConnected() 
    {
        // Should only report true if there is a functioning pipe and we aren't in a reconnect phase
        if (this.session != null) {
            return this.session.isOpen();
        } else {
            return false;
        }
    }

    public void connectAndWait(long timeoutMs) throws InterruptedException
    {
        this.connect();
        this.waitForConnection(timeoutMs);
    }

    public void connect() 
    {
        this.ensureKeepAliveRunning();
        this.attemptConnect();
    }

    private void ensureKeepAliveRunning()
    {
        synchronized (this) {
            if (this.keepAliveThread == null) {
                Thread t = new Thread(this);
                t.setName(this.getName());
                t.setDaemon(true);
                this.keepAliveThread = t;
                t.start();
            }
        }
    }

    private void attemptConnect()
    {
        if (this.isConnected()) {
            return;
        }
        if (!this.connecting.compareAndSet(false, true)) {
            // Another connect attempt is already in flight
            return;
        }
        Thread t = new Thread(() -> {
            try
            {
                RoutePutClient.this.ensureWebSocketClientStarted();
                URI upstreamUri = new URI(this.websocketUri);
                Session ses = RoutePutClient.this.webSocketClient.connect(eventsWebSocket, upstreamUri, new ClientUpgradeRequest()).get();
                if (ses instanceof WebSocketSession)
                {
                    //System.err.println("Got our WebSocketSession!");
                    this.session = (WebSocketSession) ses;
                    this.lastServerContactAt = System.currentTimeMillis();
                    this.ensureWriteWorkerRunning();
                }
            } catch (Throwable t2) {
                System.err.println("Error on connect() URI: " + this.websocketUri);
                t2.printStackTrace(System.err);
            } finally {
                RoutePutClient.this.connecting.set(false);
                RoutePutClient.this.wakeKeepAlive();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void wakeKeepAlive()
    {
        Thread t = this.keepAliveThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    public void close() 
    {
        WebSocketSession s = this.session;
        this.session = null;
        if (s != null)
        {
            try {
                s.disconnect();
            } catch (Exception e) {
                // ignore
            }
        }
        // If stayConnected is still true, wake the keep-alive so it reconnects immediately.
        this.wakeKeepAlive();
    }

    private void cleanUp()
    {
        RoutePutChannel.removeFromAllChannels(this);
        // Fail any blob fetch/send waiting on this connection so callers don't hang.
        BLOBManager.failPendingTransfersForSession(this, new java.io.IOException("connection closed"));
        this.stopWriteWorker();
        RoutePutClient.this.keepAliveThread = null;
    }

    // Prune remote sessions on this link that the server's authoritative roster no longer
    // lists; never prunes self. Shared by the handshake and the periodic roster response.
    private void reconcileRoster(RoutePutChannel channel, JSONArray roster)
    {
        if (channel == null)
            return;
        java.util.HashSet<String> rosterIds = new java.util.HashSet<String>();
        if (roster != null) {
            for (int i = 0; i < roster.length(); i++) {
                String mid = roster.optString(i, null);
                if (mid != null) rosterIds.add(mid);
            }
        }
        rosterIds.add(this.connectionId);
        RoutePutRemoteSession.reconcileChannelMembers(this, channel, rosterIds);
    }

    public void handleWebSocketEvent(RoutePutMessage j)
    {
        this.lastServerContactAt = System.currentTimeMillis();
        if (j.isType(RoutePutMessage.TYPE_CONNECTION_ID)) {
            this.connectionId = j.getRoutePutMeta().optString("connectionId", null);
            //System.err.println("Server Handed connectionId: " + this.connectionId);
            this.remoteIP = j.getRoutePutMeta().optString("remoteIP", null);
            if (j.hasMetaField("properties")) {
                this.properties = j.getRoutePutMeta().optJSONObject("properties");
            }
            if (j.hasMetaField("channelProperties")) {
                this.getDefaultChannel().mergeProperties(j.getRoutePutMeta().optJSONObject("channelProperties"));
            }
            if (this.collector) {
                RoutePutMessage rpm = new RoutePutMessage();
                rpm.setRequest("becomeCollector");
                this.send(rpm);
            }
            // Reconcile against the server's authoritative roster so remote sessions whose
            // leave we missed while disconnected don't linger as duplicates.
            if (j.hasMetaField("channelMembers")) {
                this.reconcileRoster(this.getDefaultChannel(), j.getRoutePutMeta().optJSONArray("channelMembers"));
            }
            this.getDefaultChannel().addMember(this);
        } else if (j.isType(RoutePutMessage.TYPE_RESPONSE)) {
            if ("subscribe".equals(j.getResponse()))
            {
                j.getRoutePutChannel().mergeProperties(j.getRoutePutMeta().optJSONObject("channelProperties"));
            }
            else if ("blobCheck".equals(j.getResponse()))
            {
                BLOBManager.handleBlobCheckResponse(this, j);
            }
        } else if (j.isType(RoutePutMessage.TYPE_MEMBER_SYNC)) {
            // Periodic authoritative roster; prune members whose leave we missed.
            this.reconcileRoster(j.getRoutePutChannel(), j.getRoutePutMeta().optJSONArray("channelMembers"));
        } else if (j.isType(RoutePutMessage.TYPE_PROPERTY_CHANGE)) {
            RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage(j);
            rppcm.processUpdates(this);
        } else if (j.isType(RoutePutMessage.TYPE_REQUEST)) {
            if ("blobCheck".equals(j.getRequest()))
            {
                BLOBManager.handleBlobCheckRequest(this, j);
            }
        } else if (j.isType(RoutePutMessage.TYPE_PONG)) {
            // do nada, just receive
        } else if (j.isType(RoutePutMessage.TYPE_PING)) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setType("pong");
            resp.setMetaField("pingTimestamp", j.getRoutePutMeta().optLong("timestamp", 0));
            resp.setMetaField("pongTimestamp", System.currentTimeMillis());
            this.send(resp);
        } else {
            if (j.isType(RoutePutMessage.TYPE_BLOB)) {
                BLOBManager.handleBlobData(this, j);
            }
            String sourceId = j.getSourceId();
            if (sourceId != null && RoutePutClient.this.listeners.size() == 0) {
                RoutePutRemoteSession.handleRoutedMessage(this, j);
            } else {
                RoutePutClient.this.listeners.parallelStream().forEach((r) -> {
                    r.onMessage(this, j);
                });
            }
        }
    }

    public void transmit(RoutePutMessage jo)
    {
        this.send(jo);
    }

    @Override
    public void send(RoutePutMessage jo) 
    {
        if (jo != null && this.session != null) 
        {
            // Never transmit a propertyChange that carries no updates (e.g. a setProperty
            // that didn't actually change anything).
            if (RoutePutMessage.TYPE_PROPERTY_CHANGE.equals(jo.getType()) && !RoutePutPropertyChangeMessage.hasUpdates(jo))
                return;
            jo.setSourceIdIfNull(this.connectionId);
            jo.setChannelIfNull(this.getDefaultChannel());
            this.writeQueue.offer(jo);
        }
    }

    private void ensureWriteWorkerRunning()
    {
        synchronized (this) {
            if (this.writeWorker == null) {
                Thread t = new Thread(this::writeLoop, "routeput-writer-" + this.connectionId);
                t.setDaemon(true);
                this.writeWorker = t;
                t.start();
            }
        }
    }

    private void stopWriteWorker()
    {
        Thread w;
        synchronized (this) {
            w = this.writeWorker;
            this.writeWorker = null;
        }
        this.writeQueue.offer(WRITE_POISON);
        if (w != null) {
            w.interrupt();
        }
    }

    private void writeLoop()
    {
        while (true)
        {
            RoutePutMessage jo;
            try {
                jo = this.writeQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                break;
            }
            if (jo == WRITE_POISON) {
                break;
            }
            WebSocketSession s = this.session;
            if (jo != null && s != null)
            {
                try
                {
                    // Block until this frame is flushed so the next write can't interleave.
                    s.getRemote().sendStringByFuture(jo.toString()).get();
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) { e.printStackTrace(System.err); }
            }
        }
    }

    public void subscribe(RoutePutChannel channel)
    {
        this.subscribe(channel, null);
    }

    public void subscribe(RoutePutChannel channel, String password)
    {
        if (password != null && !password.isEmpty())
        {
            this.channelPasswords.put(channel.getName(), password);
        }
        this.subscribedChannels.add(channel.getName());
        RoutePutMessage subscribeMessage = new RoutePutMessage();
        subscribeMessage.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
        subscribeMessage.setChannel(channel);
        subscribeMessage.setMetaField("connected",true);
        subscribeMessage.setMetaField("properties", this.getProperties());
        String pw = this.channelPasswords.get(channel.getName());
        if (pw != null) subscribeMessage.setMetaField("password", pw);
        this.transmit(subscribeMessage);
    }

    public void unsubscribe(RoutePutChannel channel)
    {
        this.subscribedChannels.remove(channel.getName());
        RoutePutMessage subscribeMessage = new RoutePutMessage();
        subscribeMessage.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
        subscribeMessage.setChannel(channel);
        subscribeMessage.setMetaField("connected", false);
        subscribeMessage.setMetaField("properties", this.getProperties());
        this.transmit(subscribeMessage);
    }

    // will request the blob from the given channel.
    public java.util.concurrent.CompletableFuture<BLOBFile> requestBlob(RoutePutChannel channel, String name)
    {
        return BLOBManager.requestBlob(this, channel, name);
    }

    // will always request the blob from the default channel if it is not available locally.
    public java.util.concurrent.CompletableFuture<BLOBFile> requestBlob(String name)
    {
        return this.requestBlob(this.getDefaultChannel(), name);
    }

    // Java equivalent of routeput.js `channel.getBlob(name)`.
    // checks if file exists locally and requests it if not.
    public java.util.concurrent.CompletableFuture<BLOBFile> getBlob(RoutePutChannel channel, String name)
    {
        return BLOBManager.getBlob(this, channel, name);
    }

    public java.util.concurrent.CompletableFuture<BLOBFile> getBlob(String name)
    {
        return this.getBlob(this.getDefaultChannel(), name);
    }

    // Remember a password so it will be attached to the next handshake or subscribe
    // targeting the given channel.
    public void setChannelPassword(String channelName, String password)
    {
        if (password == null || password.isEmpty()) this.channelPasswords.remove(channelName);
        else this.channelPasswords.put(channelName, password);
    }

    public void addMessageListener(RoutePutMessageListener r) 
    {
        if (!this.listeners.contains(r)) {
            this.listeners.add(r);
        }
    }

    public void removeMessageListener(RoutePutMessageListener r) 
    {
        if (this.listeners.contains(r)) {
            this.listeners.remove(r);
        }
    }

    public Collection<RoutePutMessageListener> getMessageListeners()
    {
        return this.listeners;
    }

    public boolean hasMessageListener(RoutePutMessageListener r)
    {
        return this.listeners.contains(r);
    }

    @WebSocket
    public class EventsWebSocket 
    {

        @OnWebSocketMessage
        public void onText(Session session, String message) throws IOException
        {
            try
            {
                RoutePutMessage jo = new RoutePutMessage(message);
                if (jo.optMetaField("squeak", false)) {
                    System.err.println("SQUEAK! " + jo.toString());
                }
                RoutePutClient.this.handleWebSocketEvent(jo);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException
        {
            // System.err.println("Connected websocket");
            if (session instanceof WebSocketSession) {
                RoutePutClient.this.session = (WebSocketSession) session;
                RoutePutClient.this.ensureKeepAliveRunning();
                RoutePutClient.this.ensureWriteWorkerRunning();
                // System.out.println(RoutePutClient.this.session.getRemoteAddress().getHostString()
                // + " connected!");
                RoutePutMessage connectionIdMessage = new RoutePutMessage();
                connectionIdMessage.setType(RoutePutMessage.TYPE_CONNECTION_ID);
                connectionIdMessage.setMetaField("connectionId", RoutePutClient.this.connectionId);
                connectionIdMessage.setMetaField("collector", RoutePutClient.this.collector);
                connectionIdMessage.setMetaField("channel", RoutePutClient.this.channel.getName());
                connectionIdMessage.setMetaField("properties", RoutePutClient.this.getProperties());
                String pw = RoutePutClient.this.channelPasswords.get(RoutePutClient.this.channel.getName());
                if (pw != null) connectionIdMessage.setMetaField("password", pw);
                RoutePutClient.this.send(connectionIdMessage);
                // Rejoin any extra channels subscribed before the drop; the handshake above
                // only restores the default channel, so without this they'd stay off-roster.
                String defaultChannelName = RoutePutClient.this.channel.getName();
                for (String channelName : RoutePutClient.this.subscribedChannels)
                {
                    if (!channelName.equals(defaultChannelName))
                    {
                        RoutePutClient.this.subscribe(RoutePutChannel.getChannel(channelName));
                    }
                }
            } else {
                // System.err.println("Not an instance of WebSocketSession");
            }
        }

        @OnWebSocketClose
        public void onClose(Session session, int status, String reason)
        {
            // System.err.println("Close websocket");
            RoutePutClient.this.session = null;
            // In-flight blob transfers can't resume across a reconnect; fail them now.
            BLOBManager.failPendingTransfersForSession(RoutePutClient.this, new java.io.IOException("connection closed"));
            if (RoutePutClient.this.stayConnected)
            {
                System.err.println("Connection Closed - Auto Reconnect");
                RoutePutClient.this.wakeKeepAlive();
            } else {
                RoutePutClient.this.cleanUp();
            }
        }

        @OnWebSocketError
        public void onError(Throwable e)
        {
            System.err.println("Connection Error - websocket");
            e.printStackTrace(System.err);
            RoutePutClient.this.session = null;
            BLOBManager.failPendingTransfersForSession(RoutePutClient.this, e);
            if (RoutePutClient.this.stayConnected) {
                System.err.println("Auto Reconnect");
                RoutePutClient.this.wakeKeepAlive();
            } else {
                RoutePutClient.this.cleanUp();
            }
        }
    }

    @Override
    public boolean isRootConnection()
    {
        return true;
    }

    @Override
    public boolean containsConnectionId(String connectionId)
    {
        // if the connectionId matches our master connection ID, and the source is not a child of this session, return false
        // this ensures packets go to upstreams
        if (this.connectionId.equals(RoutePutChannel.getMasterConnectionId()) && !RoutePutRemoteSession.isChild(this, connectionId))
            return false;
        // Check if the connectionId is a child of this session or if it matches this session's connectionId
        return this.connectionId.equals(connectionId) || RoutePutRemoteSession.isChild(this, connectionId);
    }

    // Keep alive thread
    // Pings the server on a healthy connection; when the connection is down and
    // stayConnected is true, retries with capped exponential backoff. Wakes on
    // interrupt so close()/onClose()/onError() can trigger an immediate retry.
    @Override
    public void run() 
    {
        final int minBackoffMs = 1000;
        final int maxBackoffMs = 30000;
        final int pingIntervalMs = 10000;
        int backoffMs = minBackoffMs;
        while (this.keepAliveThread == Thread.currentThread() && this.stayConnected)
        {
            try {
                if (this.isConnected())
                {
                    this.ping();
                    // isOpen() can stay true through a network hiccup that the server already
                    // ping/pong-dropped; if the server has gone silent too long, drop the stale
                    // socket so the loop reconnects and re-handshakes back onto the roster.
                    long silence = System.currentTimeMillis() - this.lastServerContactAt;
                    if (this.serverSilenceTimeoutMs > 0 && silence > this.serverSilenceTimeoutMs)
                    {
                        System.err.println("Server silent " + silence + "ms despite open socket; forcing reconnect to " + this.websocketUri);
                        this.close();
                    }
                    else
                    {
                        backoffMs = minBackoffMs;
                        this.keepAliveThread.setName(this.getName());
                        Thread.sleep(pingIntervalMs);
                    }
                }
                else if (this.connecting.get())
                {
                    // Wait briefly for the in-flight attempt to resolve
                    Thread.sleep(500);
                }
                else
                {
                    System.err.println("No connection detected by keep alive, reconnecting to " + this.websocketUri);
                    this.attemptConnect();
                    this.keepAliveThread.setName(this.getName());
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
                }
            } catch (InterruptedException ie) {
                // Woken up by a state change (close, onClose, onError, setAutoReconnect); re-evaluate immediately
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        this.keepAliveThread = null;
        if (!this.stayConnected) {
            this.cleanUp();
        }
        System.err.println("Leaving RoutePutClient keepAlive!");
    }

    public void setProperty(String key, Object value)
    {
        Object oldValue = this.properties.opt(key);
        if (this.isConnected()) {
            this.firePropertyChange(key, oldValue, value);
            // Send the change to the server; the channel broadcast path would filter us out as the source.
            RoutePutPropertyChangeMessage setPropertyMessage = new RoutePutPropertyChangeMessage();
            setPropertyMessage.addUpdate(this, key, oldValue, value);
            this.send(setPropertyMessage);
        } else {
            this.properties.put(key, value);
        }
    }

    @Override
    public JSONObject getProperties()
    {
        this.properties.put("_class", "RoutePutClient");
        this.properties.put("_version", RoutePutMain.VERSION);
        this.properties.put("_listeners", this.listeners.size());
        this.properties.put("_remoteIP", this.remoteIP);
        this.properties.put("_hostname", RoutePutChannel.getHostname());
        if (RoutePutMain.args != null)
            this.properties.put("_args", RoutePutMain.args);
        this.properties.put("_master", RoutePutChannel.getMasterConnectionId());
        return this.properties;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener)
    {
        this.propertyChangeSupport.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener)
    {
        this.propertyChangeSupport.removePropertyChangeListener(listener);
    }

    @Override
    public void firePropertyChange(String key, Object oldValue, Object newValue) {
        this.properties.put(key, newValue);
        this.propertyChangeSupport.firePropertyChange(key, oldValue, newValue);
    }
}