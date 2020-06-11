package org.openstatic.routeput.client;

import org.json.*;
import java.util.Vector;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;

import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutSession;

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

public class RoutePutClient implements RoutePutSession
{
    private String channel;
    private String websocketUri;
    private String connectionId;
    private WebSocketClient upstreamClient;
    private WebSocketSession session;
    private Vector<RoutePutMessageListener> listeners;
    private Vector<RoutePutRemoteSessionListener> remoteSessionListeners;
    protected LinkedHashMap<String, RoutePutRemoteSession> sessions;

    private boolean stayConnected;
    private JSONObject upgradeHeaders;
    private String remoteIP;
    private boolean collector;

    public RoutePutClient(String channel, String websocketUri)
    {
        this.listeners = new Vector<RoutePutMessageListener>();
        this.remoteSessionListeners = new Vector<RoutePutRemoteSessionListener>();
        this.sessions = new LinkedHashMap<String, RoutePutRemoteSession>();
        this.channel = channel;
        this.websocketUri = websocketUri;
        this.collector = false;
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

    @Override
    public String getConnectionId()
    {
        return this.connectionId;
    }

    @Override
    public String getDefaultChannel()
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
        if (this.upgradeHeaders != null)
            jo.put("upgradeHeaders", this.upgradeHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("_class", "RoutePutClient");
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
        } else {
            return false;
        }
    }

    public void connect()
    {
        this.stayConnected = true;
        SslContextFactory sec = new SslContextFactory.Client();
        sec.setValidateCerts(false);
        HttpClient httpClient = new HttpClient(sec);
        RoutePutClient.this.upstreamClient = new WebSocketClient(httpClient);
        try
        {
            EventsWebSocket socket = new EventsWebSocket();
            RoutePutClient.this.upstreamClient.start();
            URI upstreamUri = new URI(this.websocketUri);
            RoutePutClient.this.upstreamClient.connect(socket, upstreamUri, new ClientUpgradeRequest());
        } catch (Throwable t2) {
            t2.printStackTrace(System.err);
        }
    }

    public void close()
    {
        this.stayConnected = false;
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
        for(RoutePutRemoteSession remoteSession : this.sessions.values())
        {
            fireSessionClosed(remoteSession);
        }
        this.sessions.clear();
    }
    
    public void handleWebSocketEvent(RoutePutMessage j)
    {
        if (j.isResponse())
        {
            if (j.getResponse().equals("connectionId"))
            {
                this.connectionId = j.optString("connectionId", null);
                this.upgradeHeaders = j.optJSONObject("upgradeHeaders");
                this.remoteIP = j.optString("remoteIP", null);
                if (this.collector)
                {
                    RoutePutMessage rpm = new RoutePutMessage();
                    rpm.setRequest("becomeCollector");
                    this.send(rpm);
                }
            }
        } else {
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
                if (j.has("__sourceConnectStatus"))
                {
                    boolean sourceConnected = j.optBoolean("__sourceConnectStatus", false);
                    if (sourceConnected)
                    {
                        fireSessionConnected(remoteSession);
                    } else {
                        fireSessionClosed(remoteSession);
                    }
                }
                remoteSession.handleMessage(j);
            }
        }
        for (Enumeration<RoutePutMessageListener> re = ((Vector<RoutePutMessageListener>) RoutePutClient.this.listeners.clone()).elements(); re.hasMoreElements();)
        {
            try
            {
                RoutePutMessageListener r = re.nextElement();
                r.onMessage(j);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void send(JSONObject jo)
    {
        if (jo != null && this.session != null)
        {
            if (!jo.has("__sourceId"))
            {
                jo.put("__sourceId", this.connectionId);
            }
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

    public void addSessionListener(RoutePutRemoteSessionListener r)
    {
        if (!this.remoteSessionListeners.contains(r))
        {
            this.remoteSessionListeners.add(r);
        }
    }
    
    public void removeSessionListener(RoutePutRemoteSessionListener r)
    {
        if (this.remoteSessionListeners.contains(r))
        {
            this.remoteSessionListeners.remove(r);
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
                //System.out.println(RoutePutClient.this.session.getRemoteAddress().getHostString() + " connected!");
            } else {
                //System.err.println("Not an instance of WebSocketSession");
            }
        }
     
        @OnWebSocketClose
        public void onClose(Session session, int status, String reason)
        {
            //System.err.println("Close websocket");
            RoutePutClient.this.session = null;
            RoutePutClient.this.upstreamClient = null;
            RoutePutClient.this.cleanUp();
        }
     
        @OnWebSocketError
        public void onError(Throwable e)
        {
            System.err.println("Error websocket");
            e.printStackTrace(System.err);
            if (RoutePutClient.this.stayConnected)
            {
                System.err.println("Auto Reconnect");
                RoutePutClient.this.connect();
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

    private void fireSessionConnected(RoutePutRemoteSession remoteSession)
    {
        for (Enumeration<RoutePutRemoteSessionListener> re = ((Vector<RoutePutRemoteSessionListener>) RoutePutClient.this.remoteSessionListeners.clone()).elements(); re.hasMoreElements();)
        {
            try
            {
                RoutePutRemoteSessionListener r = re.nextElement();
                r.onConnect(remoteSession);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }
    private void fireSessionClosed(RoutePutRemoteSession remoteSession)
    {
        for (Enumeration<RoutePutRemoteSessionListener> re = ((Vector<RoutePutRemoteSessionListener>) RoutePutClient.this.remoteSessionListeners.clone()).elements(); re.hasMoreElements();)
        {
            try
            {
                RoutePutRemoteSessionListener r = re.nextElement();
                r.onClose(remoteSession);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }
}