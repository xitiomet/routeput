package org.openstatic.routeput.client;

import org.json.*;
import java.util.Vector;
import java.util.Collection;
import java.util.LinkedHashMap;

import org.openstatic.routeput.BLOBManager;
import org.openstatic.routeput.RoutePutChannel;
import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutSession;
import org.openstatic.routeput.RoutePutRemoteSession;
import org.openstatic.routeput.RoutePutMessageListener;

import java.io.IOException;
import java.net.URI;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
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
    private RoutePutChannel channel;
    private String websocketUri;
    private String connectionId;
    private WebSocketClient upstreamClient;
    private WebSocketSession session;
    private EventsWebSocket socket;
    private Vector<RoutePutMessageListener> listeners;
    private Vector<RoutePutSessionListener> sessionListeners;
    protected LinkedHashMap<String, RoutePutRemoteSession> sessions;

    private boolean stayConnected;
    private JSONObject upgradeHeaders;
    private JSONObject properties;
    private String remoteIP;
    private boolean collector;
    private Thread keepAliveThread;
    private Thread reconnectThread;

    public RoutePutClient(RoutePutChannel channel, String websocketUri)
    {
        this.listeners = new Vector<RoutePutMessageListener>();
        this.sessionListeners = new Vector<RoutePutSessionListener>();
        this.sessions = new LinkedHashMap<String, RoutePutRemoteSession>();
        this.channel = channel;
        this.websocketUri = websocketUri;
        this.collector = false;
        this.stayConnected = true;
        this.properties = new JSONObject();
    }

    public void setCollector(boolean v)
    {
        this.collector = v;
        if (this.isConnected())
        {
            if (this.collector)
            {
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
        this.send(pingMessage);
    }

    public void setAutoReconnect(boolean v)
    {
        this.stayConnected = v;
    }

    public boolean autoReconnect()
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
    public boolean subscribedTo(String channel)
    {
        return this.channel.equals(channel);
    }

    @Override
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.getConnectionId());
        jo.put("defaultChannel", this.getDefaultChannel());
        //jo.put("upgradeHeaders", this.upgradeHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("properties", this.properties);
        jo.put("_class", "RoutePutClient");
        //jo.put("_listeners", this.listeners.size());
        //jo.put("_sessionListeners", this.remoteSessionListeners.size());
        return jo;
    }

    @Override
    public boolean isCollector()
    {
        return this.collector;
    }

    @Override
    public boolean isConnected()
    {
        if (this.session != null)
        {
            return this.session.isOpen();
        } else if (this.upstreamClient != null) {
            return this.upstreamClient.isStarted();
        } else {
            return false;
        }
    }

    public void connect()
    {
        SslContextFactory sec = new SslContextFactory.Client();
        sec.setValidateCerts(false);
        HttpClient httpClient = new HttpClient(sec);
        RoutePutClient.this.upstreamClient = new WebSocketClient(httpClient);
        try
        {
            RoutePutClient.this.socket = new EventsWebSocket();
            RoutePutClient.this.upstreamClient.start();
            URI upstreamUri = new URI(this.websocketUri);
            RoutePutClient.this.upstreamClient.connect(socket, upstreamUri, new ClientUpgradeRequest());
        } catch (Throwable t2) {
            System.err.println("Error on connect() URI: " + this.websocketUri);
            t2.printStackTrace(System.err);
        }
    }

    public void close()
    {
        if (this.upstreamClient != null)
        {
            try 
            {
                this.upstreamClient.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void cleanUp()
    {
        fireSessionClosed(this, true);
        for(RoutePutRemoteSession remoteSession : this.sessions.values())
        {
            fireSessionClosed(remoteSession, false);
        }
        this.sessions.clear();
        RoutePutClient.this.keepAliveThread = null;
    }
    
    public void handleWebSocketEvent(RoutePutMessage j)
    {
        if (j.isType(RoutePutMessage.TYPE_CONNECTION_ID))
        {
            this.connectionId = j.optString("connectionId", null);
            this.upgradeHeaders = j.optJSONObject("upgradeHeaders");
            this.remoteIP = j.optString("remoteIP", null);
            if (j.has("properties"))
            {
                this.properties = j.optJSONObject("properties");
            }
            if (this.collector)
            {
                RoutePutMessage rpm = new RoutePutMessage();
                rpm.setRequest("becomeCollector");
                this.send(rpm);
            }
            this.getDefaultChannel().addMember(this);
            fireSessionConnected(this, true);
        } else if (j.isType(RoutePutMessage.TYPE_RESPONSE)) {
            // Handle response data
        } else if (j.isType(RoutePutMessage.TYPE_REQUEST)) {

        } else if (j.isType(RoutePutMessage.TYPE_PONG)) {
            // do nada, just receive
        } else if (j.isType(RoutePutMessage.TYPE_PING)) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setType("pong");
            resp.put("timestamp", System.currentTimeMillis());
            this.send(resp);
        } else {
            if (j.isType(RoutePutMessage.TYPE_BLOB))
            {
                BLOBManager.handleBlobData(j);
            }
            String sourceId = j.getSourceId();
            if (sourceId != null)
            {
                RoutePutRemoteSession remoteSession = null;
                if (this.sessions.containsKey(sourceId))
                {
                    remoteSession = this.sessions.get(sourceId);
                } else {
                    remoteSession = new RoutePutRemoteSession(this, sourceId);
                    this.sessions.put(sourceId, remoteSession);
                }
                remoteSession.handleMessage(j);
                if (j.isType(RoutePutMessage.TYPE_CONNECTION_STATUS))
                {
                    boolean sourceConnected = j.optBoolean("connected", false);
                    if (sourceConnected)
                    {
                        fireSessionConnected(remoteSession, false);
                    } else {
                        fireSessionClosed(remoteSession, false);
                    }
                }
            }
            RoutePutClient.this.listeners.parallelStream().forEach((r) -> {
                r.onMessage(j);
            });
        }
    }

    @Override
    public void send(RoutePutMessage jo)
    {
        if (jo != null && this.session != null)
        {
            jo.setSourceIdIfNull(this.connectionId);
            jo.setChannelIfNull(this.getDefaultChannel());
            jo.getRoutePutChannel().bumpTx();
            this.session.getRemote().sendStringByFuture(jo.toString());
        }
    }

    public void addMessageListener(RoutePutMessageListener r)
    {
        if (!this.listeners.contains(r))
        {
            this.listeners.add(r);
        }
    }
    
    public void removeMessageListener(RoutePutMessageListener r)
    {
        if (this.listeners.contains(r))
        {
            this.listeners.remove(r);
        }
    }

    public void addSessionListener(RoutePutSessionListener r)
    {
        if (!this.sessionListeners.contains(r))
        {
            this.sessionListeners.add(r);
        }
    }
    
    public void removeSessionListener(RoutePutSessionListener r)
    {
        if (this.sessionListeners.contains(r))
        {
            this.sessionListeners.remove(r);
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

    public static class EventsWebSocketServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            //System.err.println("Factory Initialized");
            //factory.getPolicy().setIdleTimeout(10000);
            factory.register(EventsWebSocket.class);
        }
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
                jo.getRoutePutChannel().bumpRx();
                RoutePutClient.this.handleWebSocketEvent(jo);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
     
        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException
        {
            //System.err.println("Connected websocket");
            if (session instanceof WebSocketSession)
            {
                RoutePutClient.this.session = (WebSocketSession) session;
                if (RoutePutClient.this.keepAliveThread == null)
                {
                    RoutePutClient.this.keepAliveThread = new Thread(RoutePutClient.this);
                    RoutePutClient.this.keepAliveThread.start();
                }
                //System.out.println(RoutePutClient.this.session.getRemoteAddress().getHostString() + " connected!");
                RoutePutMessage connectionIdMessage = new RoutePutMessage();
                connectionIdMessage.setType(RoutePutMessage.TYPE_CONNECTION_ID);
                connectionIdMessage.put("connectionId", RoutePutClient.this.connectionId);
                connectionIdMessage.put("collector", RoutePutClient.this.collector);
                connectionIdMessage.put("channel", RoutePutClient.this.channel.getName());
                connectionIdMessage.put("properties", RoutePutClient.this.properties);
                RoutePutClient.this.send(connectionIdMessage);
            } else {
                //System.err.println("Not an instance of WebSocketSession");
            }
        }
     
        @OnWebSocketClose
        public void onClose(Session session, int status, String reason)
        {
            //System.err.println("Close websocket");
            RoutePutClient.this.close();
            RoutePutClient.this.session = null;
            RoutePutClient.this.upstreamClient = null;
            if (RoutePutClient.this.stayConnected)
            {
                System.err.println("Connection Closed - Auto Reconnect");
            } else {
                RoutePutClient.this.cleanUp();
            }
        }
     
        @OnWebSocketError
        public void onError(Throwable e)
        {
            System.err.println("Connection Error - websocket");
            e.printStackTrace(System.err);
            RoutePutClient.this.close();
            RoutePutClient.this.session = null;
            RoutePutClient.this.upstreamClient = null;
            if (RoutePutClient.this.stayConnected)
            {
                System.err.println("Auto Reconnect");
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
        return this.connectionId.equals(connectionId) || this.sessions.containsKey(connectionId);
    }

    private void fireSessionConnected(RoutePutSession session, boolean local)
    {
        sessionListeners.parallelStream().forEach((r) -> {
            r.onConnect(session, local);
        });
    }
    private void fireSessionClosed(RoutePutSession session, boolean local)
    {
        sessionListeners.parallelStream().forEach((r) -> {
            r.onClose(session, local);
        });
        String cId = session.getConnectionId();
        if (this.sessions.containsKey(cId))
            this.sessions.remove(cId);
    }

    @Override
    public void run() {
        while (this.keepAliveThread != null)
        {
            try
            {
                Thread.sleep(10000);
                if (this.isConnected())
                {
                    this.ping();
                } else if (this.stayConnected) {
                    System.err.println("No connection detected by keep alive reconnecting...");
                    RoutePutClient.this.close();
                    RoutePutClient.this.session = null;
                    RoutePutClient.this.upstreamClient = null;
                    this.connect();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    public void setProperty(String key, Object value)
    {
        if (this.properties != null)
            this.properties.put(key, value);
        if (this.isConnected())
        {
            RoutePutMessage setPropertyMessage = new RoutePutMessage();
            setPropertyMessage.setRequest("setProperty");
            setPropertyMessage.put("key", key);
            setPropertyMessage.put("value", value);
            this.send(setPropertyMessage);
        }
    }

    @Override
    public String getProperty(String key, String defaultValue)
    {
        if (this.properties != null)
        {
            return this.properties.optString(key, defaultValue);
        } else {
            return defaultValue;
        }
    }

    @Override
    public JSONObject getProperties()
    {
        return this.properties;
    }
}