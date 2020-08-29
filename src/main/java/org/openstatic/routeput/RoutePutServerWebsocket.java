package org.openstatic.routeput;

import org.json.*;
import org.eclipse.jetty.websocket.common.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    protected RoutePutChannel defaultChannel;
    private Vector<String> channels = new Vector<String>();
    private Vector<RoutePutMessageListener> listeners = new Vector<RoutePutMessageListener>();
    protected LinkedHashMap<String, RoutePutRemoteSession> sessions = new LinkedHashMap<String, RoutePutRemoteSession>();
    private String path;
    private String remoteIP;
    private JSONObject httpHeaders = new JSONObject();
    private JSONObject properties = new JSONObject();
    private boolean collector = false;
    private boolean connected;
    private boolean handshakeComplete = false;
    private long pingTime;
    private long rxPackets;
    private long txPackets;
    private RoutePutMessage lastRxPacket;
    private RoutePutMessage lastTxPacket;

    protected void handleMessage(RoutePutMessage jo)
    {
        if (jo.isType(RoutePutMessage.TYPE_REQUEST))
        {
            handleRequest(jo);
        } else if (jo.isType(RoutePutMessage.TYPE_RESPONSE)) {
            // Ignore this
        } else if (jo.isType(RoutePutMessage.TYPE_PING)) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setType("pong");
            resp.setMetaField("pingTimestamp", jo.getRoutePutMeta().optLong("timestamp", 0));
            resp.setMetaField("pongTimestamp", System.currentTimeMillis());
            this.send(resp);
        } else if (jo.isType(RoutePutMessage.TYPE_PONG)) {
            long cts = System.currentTimeMillis();
            JSONObject meta = jo.getRoutePutMeta();
            if (meta.has("pingTimestamp"))
            {
                this.pingTime = (cts - meta.optLong("pingTimestamp", 0l));
            }
        } else {
            if (jo.isType(RoutePutMessage.TYPE_BLOB))
            {
                BLOBManager.handleBlobData(jo);
            }
            RoutePutServer.instance.handleIncomingEvent(jo, this);
            this.listeners.parallelStream().forEach((r) -> {
                r.onMessage(jo);
            });
            if (jo.optMetaField("echo",false) && this.websocketSession != null)
            {
                jo.removeMetaField("echo");
                this.websocketSession.getRemote().sendStringByFuture(jo.toString());
            }
        }
    }

    public void handleRequest(RoutePutMessage jo)
    {
        String routeputCommand = jo.getRequest();
        JSONObject rpm = jo.getRoutePutMeta();
        if (routeputCommand.equals("subscribe"))
        {
            RoutePutChannel chan = RoutePutChannel.getChannel(rpm.optString("channel", null));
            this.addChannel(chan.getName());
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("subscribe");
            resp.setChannel(chan);
            resp.setMetaField("channelProperties", chan.getProperties());
            this.send(resp);
        } else if (routeputCommand.equals("unsubscribe")) {
            this.removeChannel(rpm.optString("channel", null));
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("unsubscribe");
            this.send(resp);
        } else if (routeputCommand.equals("becomeCollector")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("becomeCollector");
            resp.setMetaField("collector", this.becomeCollector());
            this.send(resp);
        } else if (routeputCommand.equals("dropCollector")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("dropCollector");
            resp.setMetaField("collector", this.dropCollector());
            this.send(resp);
        } else if (routeputCommand.equals("setProperty")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("setProperty");
            String key = rpm.optString("key", null);
            Object value = rpm.opt("value");
            if (key != null)
            {
                this.properties.put(key, value);
                resp.setMetaField("key", key);
                resp.setMetaField("value", value);
            } else {
                resp.setMetaField("error", "key cannot be null");
            }
            this.send(resp);
        } else if (routeputCommand.equals("setChannelProperty")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("setChannelProperty");
            String key = rpm.optString("key", null);
            Object value = rpm.opt("value");
            if (key != null)
            {
                this.defaultChannel.setProperty(key, value);
                resp.setMetaField("key", key);
                resp.setMetaField("value", value);
            } else {
                resp.setMetaField("error", "key cannot be null");
            }
            this.send(resp);
        } else if (routeputCommand.equals("getChannelProperty")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("getChannelProperty");
            String key = rpm.optString("key", null);
            if (key != null)
            {
                resp.setMetaField("key", key);
                resp.setMetaField("value", this.defaultChannel.getProperties().opt(key));
            } else {
                resp.setMetaField("error", "key cannot be null");
            }
            this.send(resp);
        } else if (routeputCommand.equals("getProperties")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("getProperties");
            resp.setMetaField("properties", this.properties);
            this.send(resp);
        } else if (routeputCommand.equals("getChannelProperties")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("getChannelProperties");
            resp.setMetaField("properties", this.defaultChannel.getProperties());
            this.send(resp);
        } else if (routeputCommand.equals("members")) {
            String channel = jo.optString("channel", this.defaultChannel.getName());
            if (this.subscribedTo(channel))
            {
                RoutePutMessage resp = new RoutePutMessage();
                resp.setResponse("members");
                resp.setMetaField("members", RoutePutServer.instance.channelMembers(channel));
                this.send(resp);
            }
        } else if (routeputCommand.equals("blob")) {
            if (rpm.has("i") && rpm.has("of") && rpm.has("data") && rpm.has("name"))
            {
                BLOBManager.handleBlobData(jo);
            } else if (rpm.has("name")) {
                String name = rpm.optString("name", "");
                BLOBManager.sendBlob(this, name);
            }
        }
    }

    private boolean becomeCollector()
    {
        if (!this.defaultChannel.hasCollector() && this.handshakeComplete)
        {
            this.defaultChannel.setCollector(this);
            this.collector = true;
        }
        return this.collector;
    }

    private boolean dropCollector()
    {
        this.collector = false;
        if (this.defaultChannel.getCollector() == this)
        {
            this.defaultChannel.setCollector(null);
        }
        return this.collector;
    }

    @OnWebSocketMessage
    public void onText(Session session, String message) throws IOException
    {
        try
        {
            RoutePutMessage jo = new RoutePutMessage(message);
            this.rxPackets++;
            this.lastRxPacket = jo;
            if (this.handshakeComplete)
            {
                // Packets need a channel set if none
                jo.setChannelIfNull(this.defaultChannel);
                // this message has no sourceID, must be from the client directly connected
                jo.setSourceIdIfNull(this.connectionId);

                // Tracking for packets
                jo.getRoutePutChannel().bumpRx();

                String sourceId = jo.getSourceId();
                if (this.connectionId.equals(sourceId))
                {
                    // this message is definitely from the directly connected client
                    this.handleMessage(jo);
                } else if (sourceId != null) {
                    // this message probably belongs to a subconnection
                    this.handleRoutedMessage(sourceId, jo);
                }
            } else if (jo.isType(RoutePutMessage.TYPE_CONNECTION_ID)) {
                JSONObject rpm = jo.getRoutePutMeta();
                this.connectionId = rpm.optString("connectionId", null);
                this.defaultChannel = RoutePutChannel.getChannel(rpm.optString("channel", "*"));
                if (rpm.has("properties"))
                {
                    this.properties = rpm.optJSONObject("properties");
                }
                this.collector = rpm.optBoolean("collector", false);
                this.finishHandshake();
            }
        } catch (Exception e) {
            RoutePutServer.logIt(this.connectionId + " - " + message, e);
        }
    }

    public void handleRoutedMessage(String sourceId, RoutePutMessage jo)
    {
        if (jo.isType(RoutePutMessage.TYPE_CONNECTION_STATUS))
        {
            boolean c = jo.getRoutePutMeta().optBoolean("connected", false);
            if (c)
            {
                // If this is a message notifying us that a downstream client connected
                // lets find that connaction or create it and pass the message off.
                RoutePutRemoteSession remoteSession = null;
                if (this.sessions.containsKey(sourceId))
                {
                    remoteSession = this.sessions.get(sourceId);
                } else {
                    final RoutePutRemoteSession finalRemoteSession = new RoutePutRemoteSession(this, sourceId);
                    finalRemoteSession.addMessageListener(new RoutePutMessageListener(){
                        @Override
                        public void onMessage(RoutePutMessage message) {
                            RoutePutServer.instance.handleIncomingEvent(message, finalRemoteSession);
                        }
                    });
                    remoteSession = finalRemoteSession;
                    this.sessions.put(sourceId, remoteSession);
                    RoutePutServer.instance.sessions.put(sourceId, remoteSession);
                }
                remoteSession.handleMessage(jo);
            } else {
                // Seems like this is a disconnect message, lets just remove the connection
                // and pass that status along.
                if (this.sessions.containsKey(sourceId))
                {
                    RoutePutRemoteSession remoteSession = this.sessions.get(sourceId);
                    if (RoutePutServer.instance.sessions.containsKey(sourceId))
                    {
                        RoutePutServer.instance.sessions.remove(sourceId);
                    }
                    remoteSession.handleMessage(jo);
                }
            }
        } else {
            // looks like this is a normal message for a local connection
            RoutePutRemoteSession remoteSession = null;
            if (this.sessions.containsKey(sourceId))
            {
                remoteSession = this.sessions.get(sourceId);
                remoteSession.handleMessage(jo);
            }
        }
    }

    private void processHeaders(Map<String, List<String>> headersMap)
    {
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
                if ("Cookie".equals(headerName))
                {
                    Pattern cookiePattern = Pattern.compile("([^=]+)=([^\\;]*);?\\s?");
                    Matcher matcher = cookiePattern.matcher(value);
                    while (matcher.find())
                    {
                        /*
                        int groupCount = matcher.groupCount();
                        System.out.println("matched: " + matcher.group(0));
                        for (int groupIndex = 0; groupIndex <= groupCount; ++groupIndex) {
                            System.out.println("group[" + groupIndex + "]=" + matcher.group(groupIndex));
                        }*/
                        String cookieKey = matcher.group(1);
                        String cookieValue = matcher.group(2);
                        this.properties.put(cookieKey, cookieValue);
                    }
                }
            } else {
                this.httpHeaders.put(headerName, new JSONArray(values));
            }
        }
    }

    private void processParameters(Map<String, List<String>> parameterMap)
    {
        for(Iterator<String> parameterNames = parameterMap.keySet().iterator(); parameterNames.hasNext();)
        {
            String parameterName = parameterNames.next();
            List<String> values = parameterMap.get(parameterName);
            if (values.size() == 1)
            {
                String value = values.get(0);
                this.properties.put(parameterName, value);
            }
        }
    }
 
    @OnWebSocketConnect
    public void onConnect(Session session) throws IOException
    {
        this.rxPackets = 0;
        this.txPackets = 0;
        this.pingTime = -1;
        this.handshakeComplete = false;
        this.connected = true;
        this.collector = false;
        UpgradeRequest upgradeRequest = session.getUpgradeRequest();
        this.path = upgradeRequest.getRequestURI().getPath();
        this.remoteIP = session.getRemoteAddress().getAddress().getHostAddress();
        this.processHeaders(upgradeRequest.getHeaders());
        this.processParameters(upgradeRequest.getParameterMap());
        StringTokenizer st = new StringTokenizer(this.path, "/");
        while (st.hasMoreTokens())
        {
            String token = st.nextToken();
            if (token.equals("channel") && st.hasMoreTokens())
            {
                this.defaultChannel = RoutePutChannel.getChannel(st.nextToken());
            }
            if (token.equals("id") && st.hasMoreTokens())
            {
                this.connectionId = st.nextToken();
            }
            if (token.equals("collector"))
            {
                this.collector = true;
            }
        }
        //System.out.println("path: " + this.path);
        if (session instanceof WebSocketSession)
        {
            this.websocketSession = (WebSocketSession) session;
            //System.out.println(this.websocketSession.getRemoteAddress().getHostString() + " connected!");
            if (this.defaultChannel != null)
            {
                finishHandshake();
            }
        }
    }

    private void finishHandshake()
    {
        if (!this.handshakeComplete)
        {
            if (this.connectionId == null)
            {
                this.connectionId = RoutePutServer.generateBigAlphaKey(24);
            }
            RoutePutServer.instance.sessions.put(this.connectionId,this);
            this.defaultChannel.addMember(this);
            RoutePutMessage jo = new RoutePutMessage();
            jo.setSourceId(this.connectionId);
            jo.setChannel(this.defaultChannel);
            jo.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
            jo.setMetaField("connected", true);
            if (RoutePutServer.instance.settings.optBoolean("remoteIPShare",false))
                jo.setMetaField("remoteIP", this.remoteIP);
            jo.setMetaField("properties", this.properties);
            RoutePutServer.instance.handleIncomingEvent(jo, this);

            RoutePutServer.logIt("New connection to " + this.defaultChannel + " from " + this.remoteIP + " as " + this.connectionId);
            
            RoutePutMessage jo2 = new RoutePutMessage();
            jo2.setType("connectionId");
            jo2.setMetaField("connectionId", this.connectionId);
            //jo2.put("upgradeHeaders", this.httpHeaders);
            jo2.setChannel(this.defaultChannel);
            jo2.setMetaField("properties", this.properties);
            jo2.setMetaField("channelProperties", this.defaultChannel.getProperties());
            if (RoutePutServer.instance.settings.optBoolean("remoteIPShare",false))
                jo2.setMetaField("remoteIP", this.remoteIP);
            this.send(jo2);

            this.defaultChannel.transmitMembers(this);
            this.handshakeComplete = true;
            if (this.collector && !this.defaultChannel.hasCollector())
            {
                this.defaultChannel.setCollector(this);
            }
        }
    }

    private void cleanUp()
    {
        this.defaultChannel.removeMember(this);
        if (RoutePutServer.instance.sessions.containsKey(this.connectionId))
            RoutePutServer.instance.sessions.remove(this.connectionId);
        for(RoutePutRemoteSession session : this.sessions.values())
        {
            String connectionId = session.getConnectionId();
            if (RoutePutServer.instance.sessions.containsKey(connectionId))
                RoutePutServer.instance.sessions.remove(connectionId);
            session.getDefaultChannel().removeMember(session);
        }
    }
 
    @OnWebSocketClose
    public void onClose(Session session, int status, String reason)
    {
        this.connected = false;
        if (this.handshakeComplete)
        {
            RoutePutMessage jo = new RoutePutMessage();
            jo.setSourceId(this.connectionId);
            jo.setChannel(this.defaultChannel.getName());
            jo.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
            jo.setMetaField("connected", false);
            RoutePutServer.instance.handleIncomingEvent(jo, this);
        }
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
    public void send(RoutePutMessage jo)
    {
        if (this.websocketSession != null && jo != null)
        {
            jo.setSourceIdIfNull(this.connectionId);
            jo.setChannelIfNull(this.getDefaultChannel());
            jo.getRoutePutChannel().bumpTx();
            this.websocketSession.getRemote().sendStringByFuture(jo.toString());
            this.txPackets++;
            this.lastTxPacket = jo;
        }
    }

    @Override
    public RoutePutChannel getDefaultChannel() 
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
        jo.put("defaultChannel", this.defaultChannel.getName());
        jo.put("socketPath", this.path);
        jo.put("channels", new JSONArray(this.channels));
        if (this.pingTime != -1)
        {
            jo.put("ping", this.pingTime);
        }
        if (this.rxPackets > 0)
        {
            jo.put("rx", this.rxPackets);
        }
        if (this.txPackets > 0)
        {
            jo.put("tx", this.txPackets);
        }
        if (RoutePutServer.instance.settings.optBoolean("showLastTxRx" , false))
        {
            jo.put("lastTx", this.lastTxPacket);
            jo.put("lastRx", this.lastRxPacket);
        }
        //jo.put("upgradeHeaders", this.httpHeaders);
        jo.put("properties", this.properties);
        jo.put("remoteIP", this.remoteIP);
        jo.put("_class", "RoutePutServerWebsocket");
        return jo;
    }

    public void ping()
    {
        RoutePutMessage pingMessage = new RoutePutMessage();
        pingMessage.setType("ping");
        pingMessage.setChannel(this.getDefaultChannel());
        pingMessage.setMetaField("timestamp", System.currentTimeMillis());
        this.send(pingMessage);
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
        return this.sessions.containsKey(connectionId) || this.connectionId.equals(connectionId);
    }

    @Override
    public boolean isConnected()
    {
        return this.connected;
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
}