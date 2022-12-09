package org.openstatic.routeput;

import org.json.*;
import org.eclipse.jetty.websocket.common.WebSocketSession;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
    private WebSocketSession websocketSession;
    protected String connectionId;
    protected RoutePutChannel defaultChannel;
    private Vector<RoutePutMessageListener> listeners = new Vector<RoutePutMessageListener>();
    private String path;
    private String remoteIP;
    private JSONObject httpHeaders = new JSONObject();
    private JSONObject properties = new JSONObject();
    private JSONObject cookies = new JSONObject();
    private boolean collector = false;
    private boolean connected;
    private boolean handshakeComplete = false;
    private long pingTime;
    private long lastPingTx;
    private long lastPongRx;
    private long rxPackets;
    private long txPackets;
    private RoutePutMessage lastRxPacket;
    private RoutePutMessage lastTxPacket;

    private void handleMessage(RoutePutMessage jo)
    {
            RoutePutChannel channel = jo.getRoutePutChannel();
            
            channel.onMessage(this, jo);
            this.listeners.parallelStream().forEach((r) -> {
                r.onMessage(this, jo);
            });
            if (jo.optMetaField("echo", false) && this.websocketSession != null) {
                jo.removeMetaField("echo");
                this.websocketSession.getRemote().sendStringByFuture(jo.toString());
            }
    }

    public void handleRequest(RoutePutMessage jo)
    {
        String routeputCommand = jo.getRequest();
        JSONObject rpm = jo.getRoutePutMeta();
        if (routeputCommand.equals("subscribe")) {
            RoutePutChannel chan = RoutePutChannel.getChannel(rpm.optString("channel", null));
            this.addChannel(chan.getName());
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("subscribe", jo);
            resp.setChannel(chan);
            resp.setMetaField("channelProperties", chan.getProperties());
            resp.setMetaField("channelBlobs", chan.getBlobs());
            this.send(resp);
        } else if (routeputCommand.equals("unsubscribe")) {
            this.removeChannel(rpm.optString("channel", null));
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("unsubscribe", jo);
            this.send(resp);
        } else if (routeputCommand.equals("becomeCollector")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("becomeCollector", jo);
            resp.setMetaField("collector", this.becomeCollector());
            this.send(resp);
        } else if (routeputCommand.equals("dropCollector")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("dropCollector", jo);
            resp.setMetaField("collector", this.dropCollector());
            this.send(resp);
        } else if (routeputCommand.equals("setChannelProperty")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("setChannelProperty", jo);
            String key = rpm.optString("key", null);
            Object value = rpm.opt("value");
            if (key != null) {
                this.defaultChannel.setProperty(key, value);
                resp.setMetaField("key", key);
                resp.setMetaField("value", value);
            } else {
                resp.setMetaField("error", "key cannot be null");
            }
            this.send(resp);
        } else if (routeputCommand.equals("getChannelProperty")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("getChannelProperty", jo);
            String key = rpm.optString("key", null);
            if (key != null) {
                resp.setMetaField("key", key);
                resp.setMetaField("value", this.defaultChannel.getProperties().opt(key));
            } else {
                resp.setMetaField("error", "key cannot be null");
            }
            this.send(resp);
        } else if (routeputCommand.equals("getSessionProperties")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("getProperties", jo);
            resp.setMetaField("properties", this.properties);
            this.send(resp);
        } else if (routeputCommand.equals("getChannelProperties")) {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("getChannelProperties", jo);
            resp.setMetaField("properties", this.defaultChannel.getProperties());
            this.send(resp);
        } else if (routeputCommand.equals("members")) {
            RoutePutChannel channel = jo.getRoutePutChannel();
            if (channel.hasMember(this)) {
                RoutePutMessage resp = new RoutePutMessage();
                resp.setResponse("members", jo);
                resp.setMetaField("members", channel.membersAsJSONArray());
                this.send(resp);
            }
        } else if (routeputCommand.equals("upstream")) {
            RoutePutChannel channel = jo.getRoutePutChannel();
            if (channel.hasMember(this)) {
                String uri = jo.optString("uri", null);
                if (uri != null) {
                    RoutePutSession upstreamSession = RoutePutServer.instance.connectUpstream(channel, uri);
                    if (upstreamSession != null) {
                        RoutePutMessage resp = new RoutePutMessage();
                        resp.setResponse("upstream", jo);
                        resp.setMetaField("upstream", upstreamSession.toJSONObject());
                        this.send(resp);
                    }
                }
            }
        } else if (routeputCommand.equals("blob")) {
            if (rpm.has("i") && rpm.has("of") && rpm.has("data") && rpm.has("name")) {
                BLOBManager.handleBlobData(this, jo);
            } else if (rpm.has("name")) {
                BLOBManager.fetchBlob(this, jo);
            }
        } else if (routeputCommand.equals("blobInfo")) {
            String name = rpm.optString("name", "");
            String context = rpm.optString("context");
            RoutePutMessage resp = new RoutePutMessage();
            resp.setResponse("blobInfo", jo);
            resp.setMetaField("name", name);
            resp.setChannel(jo.getRoutePutChannel());
            if (context != null)
            {
                resp.setMetaField("context", context);
            }
            BLOBFile blobFile = BLOBManager.resolveBlob(context, name);
            if (blobFile != null)
            {
                resp.mergeRouteputMeta(blobFile.toJSONObject());
            } else {
                resp.setMetaField("exists", false);
            }
            this.send(resp);
        } else {
            RoutePutMessage errorMsg = new RoutePutMessage();
            errorMsg.setChannel(jo.getChannel());
            errorMsg.setType(RoutePutMessage.TYPE_LOG_ERROR);
            errorMsg.setRef(jo);
            errorMsg.put("text", "Uknown request type \"" + routeputCommand + "\"");
            this.send(errorMsg);
        }
    }

    private boolean becomeCollector()
    {
        if (!this.defaultChannel.hasCollector() && this.handshakeComplete) {
            this.defaultChannel.setCollector(this);
            this.collector = true;
        }
        return this.collector;
    }

    private boolean dropCollector()
    {
        this.collector = false;
        if (this.defaultChannel.getCollector() == this) {
            this.defaultChannel.setCollector(null);
        }
        return this.collector;
    }

    @OnWebSocketMessage
    public void onText(Session session, String message) throws IOException
    {
        if (message.startsWith("{") && message.endsWith("}"))
        {
            try {
                RoutePutMessage jo = new RoutePutMessage(message);
                if (jo.optMetaField("squeak", false)) {
                    System.err.println("SQUEAK! " + jo.toString());
                }
                this.rxPackets++;
                this.lastRxPacket = jo;
                if (jo.isType(RoutePutMessage.TYPE_PROPERTY_CHANGE))
                {
                    RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage(jo);
                    rppcm.processUpdates(this);
                } else if (this.handshakeComplete) {
                    // this message has no sourceID, must be from the client directly connected
                    jo.setSourceIdIfNull(this.connectionId);
                    String sourceId = jo.getSourceId();
                    
                    if (this.connectionId.equals(sourceId)) {
                        // this message is definitely from the directly connected client
                        // Lets update any session properties this packet may change ASAP
                        if (jo.hasMetaField("setSessionProperty")) {
                            RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
                            rppcm.setSource(this);
                            JSONObject storeRequest = jo.getRoutePutMeta().optJSONObject("setSessionProperty");
                            for (String k : storeRequest.keySet()) {
                                String v = storeRequest.getString(k);
                                Object oldValue = this.properties.opt(k);
                                Object newValue = jo.getPathValue(v);
                                rppcm.addUpdate(this, k, oldValue, newValue);
                            }
                            rppcm.processUpdates(this);
                            jo.removeMetaField("setSessionProperty");
                        }

                        if (jo.isType(RoutePutMessage.TYPE_REQUEST)) {
                            handleRequest(jo);
                        } else if (jo.isType(RoutePutMessage.TYPE_RESPONSE)) {
                            // Ignore this
                        } else if (jo.isType(RoutePutMessage.TYPE_PING)) {
                            RoutePutMessage resp = new RoutePutMessage();
                            resp.setType("pong");
                            resp.setRef(jo);
                            resp.setMetaField("pingTimestamp", jo.getRoutePutMeta().optLong("timestamp", 0));
                            resp.setMetaField("pongTimestamp", System.currentTimeMillis());
                            this.send(resp);
                        } else if (jo.isType(RoutePutMessage.TYPE_PONG)) {
                            long cts = System.currentTimeMillis();
                            this.lastPongRx = cts;
                            JSONObject meta = jo.getRoutePutMeta();
                            if (meta.has("pingTimestamp")) {
                                long oldPing = this.pingTime;
                                this.pingTime = (cts - meta.optLong("pingTimestamp", 0l));
                                if (this.pingTime != -1) {
                                    RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
                                    rppcm.addUpdate(this, "_ping", oldPing, this.pingTime).processUpdates(this);
                                }
                            }
                        } else if (jo.hasChannel()) {
                            this.handleMessage(jo);
                        } else if (!jo.isType(RoutePutMessage.TYPE_BLOB)) {
                            RoutePutServer.logWarning("Lost Message " + jo.toString());
                        }
                    } else if (sourceId != null) {
                        // this message probably belongs to a subconnection
                        RoutePutRemoteSession.handleRoutedMessage(this, jo);
                    }
                    // All Blob type messages should get handled even if they lack routing info
                    if (jo.isType(RoutePutMessage.TYPE_BLOB)) {
                        BLOBManager.handleBlobData(this, jo);
                    }
                } else if (jo.isType(RoutePutMessage.TYPE_CONNECTION_ID)) {
                    JSONObject rpm = jo.getRoutePutMeta();
                    this.connectionId = rpm.optString("connectionId", null);
                    this.defaultChannel = RoutePutChannel.getChannel(rpm.optString("channel", "*"));
                    if (rpm.has("properties")) {
                        this.mergeProperties(rpm.optJSONObject("properties"));
                    }
                    this.collector = rpm.optBoolean("collector", false);
                    this.finishHandshake();
                }
            } catch (Exception e) {
                RoutePutServer.logError(this.connectionId + " - " + message, e);
            }
        } else {
            RoutePutServer.logWarning("(" + this.connectionId + ") Unknown Incoming Data:" + message);
        }
    }

    private void processHeaders(Map<String, List<String>> headersMap)
    {
        for (Iterator<String> headerNames = headersMap.keySet().iterator(); headerNames.hasNext();) 
        {
            String headerName = headerNames.next();
            List<String> values = headersMap.get(headerName);
            if (values.size() == 1) {
                String value = values.get(0);
                this.httpHeaders.put(headerName, value);
                if ("X-Real-IP".equals(headerName)) {
                    this.remoteIP = value;
                }
                if ("Cookie".equals(headerName)) {
                    Pattern cookiePattern = Pattern.compile("([^=]+)=([^\\;]*);?\\s?");
                    Matcher matcher = cookiePattern.matcher(value);
                    while (matcher.find()) {
                        /*
                         * int groupCount = matcher.groupCount(); System.out.println("matched: " +
                         * matcher.group(0)); for (int groupIndex = 0; groupIndex <= groupCount;
                         * ++groupIndex) { System.out.println("group[" + groupIndex + "]=" +
                         * matcher.group(groupIndex)); }
                         */
                        String cookieKey = matcher.group(1);
                        String cookieValue = matcher.group(2);
                        this.cookies.put(cookieKey, cookieValue);
                    }
                }
            } else {
                this.httpHeaders.put(headerName, new JSONArray(values));
            }
        }
    }

    public void mergeProperties(JSONObject props)
    {
        if (props != null)
        {
            for(String key : props.keySet())
            {
                Object oldValue = this.properties.opt(key);
                Object newValue = props.opt(key);
                this.properties.put(key, newValue);
                this.firePropertyChange(key, oldValue, newValue);
            }
        }
    }

    private void processParameters(Map<String, List<String>> parameterMap)
    {
        for (Iterator<String> parameterNames = parameterMap.keySet().iterator(); parameterNames.hasNext();) {
            String parameterName = parameterNames.next();
            List<String> values = parameterMap.get(parameterName);
            if (values.size() == 1) {
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
        this.lastPingTx = 0;
        this.lastPongRx = System.currentTimeMillis();
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
            if (token.equals("channel") && st.hasMoreTokens()) {
                this.defaultChannel = RoutePutChannel.getChannel(st.nextToken());
            }
            if (token.equals("id") && st.hasMoreTokens()) {
                this.connectionId = st.nextToken();
            }
            if (token.equals("collector")) {
                this.collector = true;
            }
        }
        // System.out.println("path: " + this.path);
        if (session instanceof WebSocketSession) {
            this.websocketSession = (WebSocketSession) session;
            // System.out.println(this.websocketSession.getRemoteAddress().getHostString() +
            // " connected!");
            if (this.defaultChannel != null) {
                finishHandshake();
            }
        }
    }

    public String getRemoteIP()
    {
        return this.remoteIP;
    }

    private void finishHandshake()
    {
        if (!this.handshakeComplete)
        {
            if (this.connectionId == null)
            {
                this.connectionId = RoutePutServer.generateBigAlphaKey(10);
            }
            if (!RoutePutServer.instance.sessions.containsKey(this.connectionId))
            {
                RoutePutServer.instance.sessions.put(this.connectionId, this);
                RoutePutServer.logIt("New connection to " + this.defaultChannel + " from " + this.remoteIP + " as " + this.connectionId);
            } else {
                RoutePutSession oldRoutePutSession = RoutePutServer.instance.sessions.replace(this.connectionId, this);
                RoutePutServer.logIt("Replacing connection to " + this.defaultChannel + " from " + oldRoutePutSession.getRemoteIP() + " with " +  this.remoteIP + " as " + this.connectionId);
                RoutePutChannel.channelsWithMember(oldRoutePutSession).forEach((c) -> {
                    c.replaceMember(this.connectionId, oldRoutePutSession, this);
                });
            }
            

            RoutePutMessage jo2 = new RoutePutMessage();
            jo2.setType(RoutePutMessage.TYPE_CONNECTION_ID);
            jo2.setMetaField("connectionId", this.connectionId);
            jo2.setChannel(this.defaultChannel);
            jo2.setMetaField("properties", this.properties);
            JSONObject channelProperties = this.defaultChannel.getProperties();
            int channelPropertiesSize = channelProperties.toString().length();
            boolean propertiesOversized = channelPropertiesSize > 512;
            if (!propertiesOversized)
                jo2.setMetaField("channelProperties", channelProperties);
            else
                jo2.setMetaField("channelProperties", new JSONObject());
            jo2.setMetaField("remoteIP", this.remoteIP);
            jo2.setMetaField("serverHostname", RoutePutChannel.getHostname());
            this.send(jo2);

            if (propertiesOversized)
            {
                RoutePutPropertyChangeMessage.buildSmallUpdatesFor(this.defaultChannel).forEach((rppcm) -> {
                    this.send(rppcm);
                });
            }

            this.defaultChannel.addMember(this);
            this.handshakeComplete = true;
            if (this.collector && !this.defaultChannel.hasCollector()) {
                this.defaultChannel.setCollector(this);
            }
        }
    }

    private void cleanUp()
    {
        RoutePutChannel.removeFromAllChannels(this);
        if (RoutePutServer.instance.sessions.containsKey(this.connectionId))
            RoutePutServer.instance.sessions.remove(this.connectionId);
    }

    @OnWebSocketClose
    public void onClose(Session session, int status, String reason)
    {
        this.connected = false;
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
            try
            {
                jo.setSourceIdIfNull(this.connectionId);
                //jo.setChannelIfNull(this.getDefaultChannel());
                this.websocketSession.getRemote().sendStringByFuture(jo.toString());
                this.txPackets++;
                this.lastTxPacket = jo;
            } catch (Exception e) { RoutePutServer.logError(e); }
        }
    }

    @Override
    public RoutePutChannel getDefaultChannel()
    {
        return this.defaultChannel;
    }

    public void addChannel(String channelName)
    {
        if (channelName != null) {
            RoutePutChannel channel = RoutePutChannel.getChannel(channelName);
            if (!channel.hasMember(this))
                channel.addMember(this);
        }
    }

    public void removeChannel(String channelName)
    {
        if (channelName != null)
        {
            RoutePutChannel channel = RoutePutChannel.getChannel(channelName);
            if (channel.hasMember(this))
                channel.removeMember(this);
        }
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
        jo.put("defaultChannel", this.defaultChannel.getName());
        jo.put("socketPath", this.path);
        List<String> channels = RoutePutChannel.channelsWithMember(this).stream().map((c) -> {
            return c.getName();
        }).collect(Collectors.toList());
        jo.put("channels", new JSONArray(channels));
        
        if (this.rxPackets > 0) {
            jo.put("rx", this.rxPackets);
        }
        if (this.txPackets > 0) {
            jo.put("tx", this.txPackets);
        }
        if (RoutePutServer.instance.routeputDebug.getProperties().optBoolean("showLastTxRx", false)) {
            jo.put("lastTx", this.lastTxPacket);
            jo.put("lastRx", this.lastRxPacket);
        }
        jo.put("properties", this.properties);
        jo.put("cookies", this.cookies);
        return jo;
    }

    public void ping() 
    {
        long ts = System.currentTimeMillis();
        if (this.lastPingTx > 0)
        {
            // Log the ping if needed
            long delay = (this.lastPingTx - this.lastPongRx);
            if (RoutePutServer.instance.settings.optBoolean("logPings", false))
            {
                RoutePutServer.logIt("PING (" + this.connectionId + ") lastPingTx=" + String.valueOf(this.lastPingTx) + " lastPongRx=" + String.valueOf(this.lastPongRx) + " delay=" + String.valueOf(delay) + "ms");
            }
            long ppDelay = RoutePutServer.instance.settings.optLong("pingPongSecs", 20l) * 1000l;
            // Check to see if the time since the last ping went unresponded exceeds the known ping
            if (delay > this.pingTime)
            {
                long oldPing = this.pingTime;
                this.pingTime = delay;
                RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
                rppcm.addUpdate(this, "_ping", oldPing, this.pingTime).processUpdates(this);
            }
            // If this is the second unresponded ping, lets kill the conneciton
            if (delay > (ppDelay * 2))
            {
                RoutePutServer.logWarning("(" + this.connectionId + ") Dropping connection due to ping/pong timeout " + delay + "ms");
                this.websocketSession.close();
                this.connected = false;
                this.cleanUp();
                return;
            }
        }
        this.lastPingTx = ts;
        RoutePutMessage pingMessage = new RoutePutMessage();
        pingMessage.setType("ping");
        //pingMessage.setChannel(this.getDefaultChannel());
        pingMessage.setMetaField("timestamp", ts);
        this.send(pingMessage);
    }

    @Override
    public boolean isRootConnection() 
    {
        return true;
    }

    @Override
    public boolean containsConnectionId(String connectionId)
    {
        return RoutePutRemoteSession.isChild(this, connectionId) || this.connectionId.equals(connectionId);
    }

    @Override
    public boolean isConnected()
    {
        return this.connected;
    }

    @Override
    public JSONObject getProperties()
    {
        this.properties.put("_remoteIP", this.remoteIP);
        this.properties.put("_class", "RoutePutServerWebsocket");
        this.properties.put("_listeners", this.listeners.size());
        this.properties.put("_connected", this.isConnected());
        return this.properties;
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
    public void firePropertyChange(String key, Object oldValue, Object newValue)
    {
        this.properties.put(key, newValue);
        this.propertyChangeSupport.firePropertyChange(key, oldValue, newValue);
    }
}