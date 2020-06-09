package org.openstatic.routeput.client;

import org.json.*;
import java.util.Vector;
import java.util.Collection;
import java.util.Enumeration;
import org.openstatic.routeput.RoutePutMessage;

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

public class RoutePutClient
{
    private String channel;
    private String websocketUri;
    private String connectionId;
    private WebSocketClient upstreamClient;
    private WebSocketSession session;
    private Vector<RoutePutMessageListener> listeners;
    private boolean stayConnected;

    public RoutePutClient(String channel, String websocketUri)
    {
        this.listeners = new Vector<RoutePutMessageListener>();
        this.channel = channel;
        this.websocketUri = websocketUri;
        System.err.println("Upstream: " + this.websocketUri);
    }

    public String getConnectionId()
    {
        return this.connectionId;
    }

    public String getChannel()
    {
        return this.channel;
    }

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
            t2.printStackTrace();
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

    public void handleWebSocketEvent(RoutePutMessage j)
    {
        //System.err.println("CLIENT " + this.connectionId + " Recieved: " + j.toString());
        if (j.isResponse())
        {
            if (j.getResponse().equals("connectionId"))
            {
                this.connectionId = j.optString("connectionId", null);
                //System.err.println("ConnectionId sent by server: " + this.connectionId);
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

    public void send(JSONObject jo)
    {
        if (jo != null && this.session != null)
        {
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
            if (session instanceof WebSocketSession)
            {
                RoutePutClient.this.session = (WebSocketSession) session;
                System.out.println(RoutePutClient.this.session.getRemoteAddress().getHostString() + " connected!");
            } else {
                System.err.println("Not an instance of WebSocketSession");
            }
        }
     
        @OnWebSocketClose
        public void onClose(Session session, int status, String reason)
        {
            RoutePutClient.this.session = null;
            RoutePutClient.this.upstreamClient = null;
        }
     
        @OnWebSocketError
        public void onError(Throwable e)
        {
            if (RoutePutClient.this.stayConnected)
            {
                System.err.println("Auto Reconnect");
                RoutePutClient.this.connect();
            }
        }
    }
}