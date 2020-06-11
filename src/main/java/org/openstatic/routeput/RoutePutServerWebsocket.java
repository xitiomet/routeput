package org.openstatic.routeput;

import org.json.*;
import org.eclipse.jetty.websocket.common.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;

import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class RoutePutServerWebsocket implements RoutePutSession
{
    private WebSocketSession websocketSession;
    protected String connectionId;
    protected String defaultChannel;
    private Vector<String> channels = new Vector<String>();
    private HashMap<String, StringBuffer> blobStorage = new HashMap<String, StringBuffer>();
    protected LinkedHashMap<String, RoutePutLocalSession> sessions = new LinkedHashMap<String, RoutePutLocalSession>();
    private String path;
    private String remoteIP;
    private JSONObject httpHeaders;
    private boolean collector = false;
    private boolean connected;

    protected void handleMessage(RoutePutMessage jo)
    {
        if (!jo.has("__eventChannel"))
        {
            jo.put("__eventChannel", this.defaultChannel);
        }
        if (!jo.has("__sourceId"))
        {
            jo.put("__sourceId", this.connectionId);
        }

        if (jo.isRequest())
        {
            handleRequest(jo);
        } else {
            RoutePutServer.instance.handleIncomingEvent(jo, this);
        }
    }

    public void handleRequest(RoutePutMessage jo)
    {
        String routeputCommand = jo.getRequest();
        if (routeputCommand.equals("subscribe"))
        {
            this.addChannel(jo.optString("channel", null));
        } else if (routeputCommand.equals("unsubscribe")) {
            this.removeChannel(jo.optString("channel", null));
        } else if (routeputCommand.equals("becomeCollector")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.put("__response", "becomeCollector");
            resp.put("collector", this.becomeCollector());
            this.send(resp);
        } else if (routeputCommand.equals("members")) {
            String channel = jo.optString("channel", this.defaultChannel);
            if (this.subscribedTo(channel))
            {
                JSONObject resp = new JSONObject();
                resp.put("__response", "members");
                resp.put("members", RoutePutServer.instance.channelMembers(channel));
                this.send(resp);
            }
        } else if (routeputCommand.equals("blob")) {
            if (jo.has("i") && jo.has("of") && jo.has("data") && jo.has("name"))
            {
                int i = jo.optInt("i", 0);
                int of = jo.optInt("of", 0);
                String name = jo.optString("name", "");
                StringBuffer sb = new StringBuffer();
                if (i == 1)
                {
                    this.blobStorage.put(name, sb);
                } else {
                    sb = this.blobStorage.get(name);
                }
                sb.append(jo.optString("data",""));
                if (i == of)
                {
                    RoutePutServer.saveBase64Blob(name, sb);
                    this.blobStorage.remove(name);
                    RoutePutServer.logIt("Received Blob: " + name +
                            " on " + jo.optString("__eventChannel", this.defaultChannel) +
                            " from " + this.getConnectionId());
                    JSONObject resp = new JSONObject();
                    resp.put("__response", "blob");
                    resp.put("name", name);
                    this.send(resp);
                }
            } else if (jo.has("name")) {
                String name = jo.optString("name", "");
                StringBuffer sb = RoutePutServer.loadBase64Blob(name);
                this.transmitBlobChunks(name, sb);
            }
        }
    }

    // Send a chunked blob to client from byte array
    public void sendBlob(String name, String contentType, byte[] bytes)
    {
        StringBuffer sb = new StringBuffer();
        sb.append("data:" + contentType + ";base64,");
        sb.append(java.util.Base64.getEncoder().encodeToString(bytes));
        transmitBlobChunks(name, sb);
    }
    
    // Transmit a blob to this client shouldnt be called except by other sendBlob methods
    private void transmitBlobChunks(String name, StringBuffer sb)
    {
        int size = sb.length();
        int chunkSize = 4096;
        int numChunks = (size + chunkSize - 1) / chunkSize;
        for (int i = 0; i < numChunks; i++)
        {
            JSONObject mm = new JSONObject();
            mm.put("__response", "blob");
            mm.put("name", name);
            mm.put("i", i+1);
            mm.put("of", numChunks);
            int start = i*chunkSize;
            int end = start + chunkSize;
            if (end > size)
                end = size;
            mm.put("data", sb.substring(start,end));
            this.send(mm);
        }
    }

    private boolean becomeCollector()
    {
        if (!RoutePutServer.instance.collectors.containsKey(this.defaultChannel))
        {
            RoutePutServer.instance.collectors.put(this.defaultChannel, this);
            this.collector = true;
        }
        return this.collector;
    }

    @OnWebSocketMessage
    public void onText(Session session, String message) throws IOException
    {
        try
        {
            RoutePutMessage jo = new RoutePutMessage(message);
            String sourceId = jo.getSourceId();
            if (this.connectionId.equals(sourceId))
            {
                // this message is definitely from the directly connected client
                this.handleMessage(jo);
            } else if (sourceId != null) {
                // this message probably belongs to a subconnection
                this.handleRoutedMessage(sourceId, jo);
            } else {
                // this message has no sourceID, must be from the client directly connected
                this.handleMessage(jo);
            }
        } catch (Exception e) {
            RoutePutServer.logIt(this.connectionId + " - " + message, e);
        }
    }

    public void handleRoutedMessage(String sourceId, RoutePutMessage jo)
    {
        if (jo.has("__sourceConnectStatus"))
        {
            boolean c = jo.optBoolean("__sourceConnectStatus", false);
            if (c)
            {
                // If this is a message notifying us that a downstream client connected
                // lets find that connaction or create it and pass the message off.
                RoutePutLocalSession localSession = null;
                if (this.sessions.containsKey(sourceId))
                {
                    localSession = this.sessions.get(sourceId);
                } else {
                    localSession = new RoutePutLocalSession(this, sourceId);
                    this.sessions.put(sourceId, localSession);
                    RoutePutServer.instance.sessions.put(sourceId, localSession);
                }
                localSession.handleMessage(jo);
            } else {
                // Seems like this is a disconnect message, lets just remove the connection
                // and pass that status along.
                if (this.sessions.containsKey(sourceId))
                {
                    RoutePutLocalSession localSession = this.sessions.get(sourceId);
                    if (RoutePutServer.instance.sessions.containsKey(sourceId))
                    {
                        RoutePutServer.instance.sessions.remove(sourceId);
                    }
                    localSession.handleMessage(jo);
                }
            }
        } else {
            // looks like this is a normal message for a local connection
            RoutePutLocalSession localSession = null;
            if (this.sessions.containsKey(sourceId))
            {
                localSession = this.sessions.get(sourceId);
                localSession.handleMessage(jo);
            }
        }
    }
 
    @OnWebSocketConnect
    public void onConnect(Session session) throws IOException
    {
        this.connected = true;
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
                this.becomeCollector();
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
            RoutePutServer.instance.sessions.put(this.connectionId,this);
            
            RoutePutMessage jo = new RoutePutMessage();
            jo.setSourceId(this.connectionId);
            jo.setChannel(this.defaultChannel);
            jo.put("__sourceConnectStatus", true);
            jo.put("remoteIP", this.remoteIP);
            RoutePutServer.instance.handleIncomingEvent(jo, this);

            RoutePutServer.logIt("New connection to " + this.defaultChannel + " from " + this.remoteIP + " as " + this.connectionId);
            
            JSONObject jo2 = new JSONObject();
            jo2.put("__response", "connectionId");
            jo2.put("connectionId", this.connectionId);
            jo2.put("upgradeHeaders", this.httpHeaders);
            jo2.put("remoteIp", this.remoteIP);
            this.send(jo2);
        }
    }

    private void cleanUp()
    {
        if (RoutePutServer.instance.sessions.containsKey(this.connectionId))
            RoutePutServer.instance.sessions.remove(this.connectionId);
        for(String connectionId : this.sessions.keySet())
        {
            if (RoutePutServer.instance.sessions.containsKey(connectionId))
                RoutePutServer.instance.sessions.remove(connectionId);
        }
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
 
    @OnWebSocketClose
    public void onClose(Session session, int status, String reason)
    {
        this.connected = false;
        RoutePutMessage jo = new RoutePutMessage();
        jo.setSourceId(this.connectionId);
        jo.setChannel(this.defaultChannel);
        jo.put("__sourceConnectStatus", false);
        RoutePutServer.instance.handleIncomingEvent(jo, this);
        this.cleanUp();
    }
    
    @OnWebSocketError
    public void onError(Session session, Throwable throwable) 
    {
        this.connected = false;
        this.cleanUp();
    }
    
    public WebSocketSession getWebsocketSession()
    {
        return this.websocketSession;
    }

    @Override
    public void send(JSONObject jo)
    {
        if (this.websocketSession != null && jo != null)
        {
            this.websocketSession.getRemote().sendStringByFuture(jo.toString());
        }
    }

    @Override
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

    @Override
    public boolean subscribedTo(String channel)
    {
        return (this.channels.contains(channel) ||      // Are we subscribed to the channel?
                this.defaultChannel.equals(channel) ||  // Is our default channel the channel?
                this.defaultChannel.equals("*"));       // Is our default channel * ?
    }

    @Override
    public String getConnectionId() 
    {
        return this.connectionId;
    }

    @Override
    public JSONObject toJSONObject() 
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.connectionId);
        jo.put("collector", this.isCollector());
        jo.put("defaultChannel", this.defaultChannel);
        jo.put("socketPath", this.path);
        jo.put("channels", new JSONArray(this.channels));
        jo.put("upgradeHeaders", this.httpHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("_class", "RoutePutServerWebsocket");
        return jo;
    }

    @Override
    public boolean isCollector()
    {
        return this.collector;
    }

    @Override
    public boolean isRootConnection()
    {
        return true;
    }

    @Override
    public boolean containsConnectionId(String connectionId)
    {
        // TODO Auto-generated method stub
        return this.sessions.containsKey(connectionId) || this.connectionId.equals(connectionId);
    }

    @Override
    public boolean isConnected()
    {
        return this.connected;
    }
}