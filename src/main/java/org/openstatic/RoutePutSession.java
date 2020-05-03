package org.openstatic;

import org.json.*;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Vector;
import java.util.StringTokenizer;

import java.io.IOException;
import java.io.BufferedReader;

@WebSocket
public class RoutePutSession
{
    private WebSocketSession websocketSession;
    private Vector<String> channels = new Vector<String>();
    private String defaultChannel = "*";
    private String path;
    private String connectionId;
    private boolean collector = false;
    
    public void send(JSONObject jo)
    {
        if (this.websocketSession != null && jo != null)
        {
            // avoid sending events to connection that sent them.
            String connectId = jo.optString("__sourceId", "");
            if (!connectId.equals(this.connectionId))
                this.websocketSession.getRemote().sendStringByFuture(jo.toString());
        }
    }
    
    @OnWebSocketMessage
    public void onText(Session session, String message) throws IOException
    {
        try
        {
            JSONObject jo = new JSONObject(message);
            if (session instanceof WebSocketSession)
            {
                if (!jo.has("__eventChannel"))
                {
                    jo.put("__eventChannel", this.defaultChannel);
                }
                jo.put("__sourceId", this.connectionId);
                RoutePutServer.instance.handleIncomingEvent(jo, this);
            } else {
                System.err.println("not instance of WebSocketSession");
            }
        } catch (Exception e) {}
    }
 
    @OnWebSocketConnect
    public void onConnect(Session session) throws IOException
    {
        this.path = session.getUpgradeRequest().getRequestURI().getPath();
        StringTokenizer st = new StringTokenizer(this.path, "/");
        while (st.hasMoreTokens())
        {
            String token = st.nextToken();
            if (token.equals("channel") && st.hasMoreTokens())
            {
                this.defaultChannel = st.nextToken();
            }
            if (token.equals("id") && st.hasMoreTokens())
            {
                this.connectionId = st.nextToken();
            }
            if (token.equals("collector"))
            {
                RoutePutServer.instance.collectors.put(this.defaultChannel, this);
                this.collector = true;
            }
        }
        if (this.connectionId == null)
        {
            this.connectionId = RoutePutServer.generateBigAlphaKey(24);
        }
        System.out.println("path: " + this.path);
        if (session instanceof WebSocketSession)
        {
            this.websocketSession = (WebSocketSession) session;
            System.out.println(this.websocketSession.getRemoteAddress().getHostString() + " connected!");
            RoutePutServer.instance.sessions.add(this);
            JSONObject jo = new JSONObject();
            jo.put("__sourceId", this.connectionId);
            jo.put("__eventChannel", this.defaultChannel);
            jo.put("__sourceConnectStatus", true);
            RoutePutServer.instance.handleIncomingEvent(jo, this);
        }
    }
 
    @OnWebSocketClose
    public void onClose(Session session, int status, String reason)
    {
        JSONObject jo = new JSONObject();
        jo.put("__sourceId", this.connectionId);
        jo.put("__eventChannel", this.defaultChannel);
        jo.put("__sourceConnectStatus", false);
        RoutePutServer.instance.handleIncomingEvent(jo, this);
        
        RoutePutServer.instance.sessions.remove(this);
        if (this.collector)
        {
            if (RoutePutServer.instance.collectors.containsKey(this.defaultChannel))
            {
                RoutePutSession collect = RoutePutServer.instance.collectors.get(this.defaultChannel);
                if (collect == this)
                {
                    RoutePutServer.instance.collectors.remove(this.defaultChannel);
                }
            }
        }
    }
    
    public boolean isCollector()
    {
        return this.collector;
    }
    
    public void addChannel(String channel)
    {
        if (!this.channels.contains(channel))
          this.channels.add(channel);
    }
    
    public void removeChannel(String channel)
    {
        if (this.channels.contains(channel))
          this.channels.remove(channel);
    }
    
    public String getConnectionId()
    {
        return this.connectionId;
    }
    
    public boolean subscribedTo(String channel)
    {
        return (this.channels.contains(channel) || "*".equals(channel) || this.defaultChannel.equals(channel)  || this.defaultChannel.equals("*"));
    }
    
    public WebSocketSession getWebsocketSession()
    {
        return this.websocketSession;
    }
    
    
}
