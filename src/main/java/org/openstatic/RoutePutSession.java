package org.openstatic;

import org.json.*;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;

import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Vector;
import java.util.StringTokenizer;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.io.BufferedReader;

@WebSocket
public class RoutePutSession
{
    private WebSocketSession websocketSession;
    private Vector<String> channels = new Vector<String>();
    private String defaultChannel = "*";
    private String path;
    private String remoteIP;
    private String connectionId;
    private boolean collector = false;
    private JSONObject httpHeaders;
    
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
                if (jo.has("__command"))
                {
                    String routeputCommand = jo.optString("__command","");
                    if (routeputCommand.equals("subscribe"))
                    {
                        this.addChannel(jo.optString("channel", null));
                    } else if (routeputCommand.equals("unsubscribe")) {
                        this.removeChannel(jo.optString("channel", null));
                    } else if (routeputCommand.equals("members")) {
                        String channel = jo.optString("channel", this.defaultChannel);
                        if (this.subscribedTo(channel))
                        {
                            JSONObject resp = new JSONObject();
                            resp.put("__commandResponse", "members");
                            resp.put("members", RoutePutServer.instance.channelMembers(channel));
                            this.send(resp);
                        }
                    }
                } else {
                    if (!jo.has("__eventChannel"))
                    {
                        jo.put("__eventChannel", this.defaultChannel);
                    }
                    jo.put("__sourceId", this.connectionId);
                    RoutePutServer.instance.handleIncomingEvent(jo, this);
                }
            } else {
                System.err.println("not instance of WebSocketSession");
            }
        } catch (Exception e) {
            
        }
    }
 
    @OnWebSocketConnect
    public void onConnect(Session session) throws IOException
    {
        UpgradeRequest upgradeRequest = session.getUpgradeRequest();
        this.path = upgradeRequest.getRequestURI().getPath();
        this.remoteIP = session.getRemoteAddress().getAddress().getHostAddress();
        Map<String,List<String>> headersMap = upgradeRequest.getHeaders();
        this.httpHeaders = new JSONObject();
        for(Iterator<String> headerNames = headersMap.keySet().iterator(); headerNames.hasNext();)
        {
            String headerName = headerNames.next();
            List<String> values = headersMap.get(headerName);
            if (values.size() == 1)
            {
                String value = values.get(0);
                this.httpHeaders.put(headerName, value);
                if ("X-Real-IP".equals(headerName))
                {
                    this.remoteIP = value;
                }
            } else {
                this.httpHeaders.put(headerName, new JSONArray(values));
            }
        }
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
        //System.out.println("path: " + this.path);
        if (session instanceof WebSocketSession)
        {
            this.websocketSession = (WebSocketSession) session;
            //System.out.println(this.websocketSession.getRemoteAddress().getHostString() + " connected!");
            RoutePutServer.instance.sessions.add(this);
            JSONObject jo = new JSONObject();
            jo.put("__sourceId", this.connectionId);
            jo.put("__eventChannel", this.defaultChannel);
            jo.put("__sourceConnectStatus", true);
            RoutePutServer.instance.handleIncomingEvent(jo, this);
            RoutePutServer.logIt("New connection to " + this.defaultChannel + " from " + this.remoteIP + " as " + this.connectionId);
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
        if (RoutePutServer.instance.sessions.contains(this))
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
    
    @OnWebSocketError
    public void onError(Session session, Throwable throwable) 
    {
        //System.err.println("Websocket error " + this.connectionId);
        //throwable.printStackTrace(System.err);
        if (RoutePutServer.instance.sessions.contains(this))
            RoutePutServer.instance.sessions.remove(this);
    }
    
    public boolean isCollector()
    {
        return this.collector;
    }
    
    public String getDefaultChannel()
    {
        return this.defaultChannel;
    }
    
    public void addChannel(String channel)
    {
        if (channel != null)
        {
            if (!this.channels.contains(channel))
                this.channels.add(channel);
        }
    }
    
    public void removeChannel(String channel)
    {
        if (channel != null)
        {
            if (this.channels.contains(channel))
              this.channels.remove(channel);
        }
    }
    
    public String getConnectionId()
    {
        return this.connectionId;
    }
    
    public boolean subscribedTo(String channel)
    {
        return (this.channels.contains(channel) ||      // Are we subscribed to the channel?
                this.defaultChannel.equals(channel) ||  // Is our default channel the channel?
                this.defaultChannel.equals("*"));       // Is our default channel * ?
    }
    
    public WebSocketSession getWebsocketSession()
    {
        return this.websocketSession;
    }
    
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.connectionId);
        jo.put("collector", this.collector);
        jo.put("defaultChannel", this.defaultChannel);
        jo.put("socketPath", this.path);
        jo.put("channels", new JSONArray(this.channels));
        jo.put("upgradeHeaders", this.httpHeaders);
        jo.put("remoteIP", this.remoteIP);
        return jo;
    }
    
}
