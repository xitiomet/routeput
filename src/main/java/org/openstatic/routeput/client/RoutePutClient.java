package org.openstatic.routeput.client;

import org.json.*;
import java.util.Vector;
import java.util.concurrent.Future;
import java.util.Collection;

import org.openstatic.routeput.BLOBManager;
import org.openstatic.routeput.RoutePutChannel;
import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutSession;
import org.openstatic.routeput.RoutePutRemoteSession;
import org.openstatic.routeput.RoutePutMessageListener;
import org.openstatic.routeput.RoutePutPropertyChangeMessage;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public RoutePutClient(RoutePutChannel channel, String websocketUri)
    {
        this.propertyChangeSupport = new PropertyChangeSupport(this);
        this.listeners = new Vector<RoutePutMessageListener>();
        this.channel = channel;
        this.websocketUri = websocketUri;
        this.collector = false;
        this.stayConnected = true;
        this.properties = new JSONObject();

        Runtime.getRuntime().addShutdownHook(new Thread() 
        { 
          public void run() 
          {
            RoutePutClient.this.stayConnected = false;
            System.err.println("Routeput client received shutdown hook!");
            RoutePutClient.this.cleanUp();
          } 
        }); 

        SslContextFactory sec = new SslContextFactory.Client();
        sec.setValidateCerts(false);
        HttpClient httpClient = new HttpClient(sec);
        RoutePutClient.this.webSocketClient = new WebSocketClient(httpClient);
        RoutePutClient.this.webSocketClient.setMaxIdleTimeout(120000);
        try
        {
            this.webSocketClient.start();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        RoutePutClient.this.eventsWebSocket = new EventsWebSocket();
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
                URI upstreamUri = new URI(this.websocketUri);
                RoutePutClient.this.eventsWebSocket = new EventsWebSocket();
                Session ses = RoutePutClient.this.webSocketClient.connect(eventsWebSocket, upstreamUri, new ClientUpgradeRequest()).get();
                if (ses instanceof WebSocketSession)
                {
                    System.err.println("Got our WebSocketSession!");
                    this.session = (WebSocketSession) ses;
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
        RoutePutClient.this.keepAliveThread = null;
    }

    public void handleWebSocketEvent(RoutePutMessage j)
    {
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
            jo.setSourceIdIfNull(this.connectionId);
            jo.setChannelIfNull(this.getDefaultChannel());
            this.session.getRemote().sendStringByFuture(jo.toString());
        }
    }

    public void subscribe(RoutePutChannel channel)
    {
        RoutePutMessage subscribeMessage = new RoutePutMessage();
        subscribeMessage.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
        subscribeMessage.setChannel(channel);
        subscribeMessage.setMetaField("connected",true);
        subscribeMessage.setMetaField("properties", this.getProperties());
        this.transmit(subscribeMessage);
    }

    public void unsubscribe(RoutePutChannel channel)
    {
        RoutePutMessage subscribeMessage = new RoutePutMessage();
        subscribeMessage.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
        subscribeMessage.setChannel(channel);
        subscribeMessage.setMetaField("connected", false);
        subscribeMessage.setMetaField("properties", this.getProperties());
        this.transmit(subscribeMessage);
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
                // System.out.println(RoutePutClient.this.session.getRemoteAddress().getHostString()
                // + " connected!");
                RoutePutMessage connectionIdMessage = new RoutePutMessage();
                connectionIdMessage.setType(RoutePutMessage.TYPE_CONNECTION_ID);
                connectionIdMessage.setMetaField("connectionId", RoutePutClient.this.connectionId);
                connectionIdMessage.setMetaField("collector", RoutePutClient.this.collector);
                connectionIdMessage.setMetaField("channel", RoutePutClient.this.channel.getName());
                connectionIdMessage.setMetaField("properties", RoutePutClient.this.properties);
                RoutePutClient.this.send(connectionIdMessage);
            } else {
                // System.err.println("Not an instance of WebSocketSession");
            }
        }

        @OnWebSocketClose
        public void onClose(Session session, int status, String reason)
        {
            // System.err.println("Close websocket");
            RoutePutClient.this.session = null;
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
                    backoffMs = minBackoffMs;
                    this.keepAliveThread.setName(this.getName());
                    Thread.sleep(pingIntervalMs);
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
        if (this.isConnected()) {
            RoutePutPropertyChangeMessage setPropertyMessage = new RoutePutPropertyChangeMessage();
            setPropertyMessage.addUpdate(this, key, this.properties.opt(key), value);
            setPropertyMessage.processUpdates(this);
        } else {
            this.properties.put(key, value);
        }
    }

    @Override
    public JSONObject getProperties()
    {
        this.properties.put("_class", "RoutePutClient");
        this.properties.put("_listeners", this.listeners.size());
        this.properties.put("_remoteIP", this.remoteIP);
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