package org.openstatic.routeput;

import org.json.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.Queue;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.LinkedBlockingQueue;

public class ApiServlet extends HttpServlet implements RoutePutSession {
    private JSONObject properties;
    private long rxPackets;
    private long txPackets;
    private Map<RoutePutChannel, Date> lastChannelInteraction;
    private Map<String, LinkedBlockingQueue<RoutePutMessage>> pendingOutbound;

    public ApiServlet() {
        this.properties = new JSONObject();
        this.properties.put("description", "Virtual Session for API GET/POST messages");
        this.rxPackets = 0;
        this.txPackets = 0;
        RoutePutServer.logIt("** API SERVLET INITIALIZED **");
        RoutePutServer.instance.apiServlet = this;
        this.lastChannelInteraction = new HashMap<RoutePutChannel, Date>();
        this.lastChannelInteraction = Collections.synchronizedMap(this.lastChannelInteraction);
        this.pendingOutbound = new HashMap<String, LinkedBlockingQueue<RoutePutMessage>>();
        //this.pendingOutbound = Collections.synchronizedMap(this.pendingOutbound);
    }

    public RoutePutMessage readRoutePutMessagePOST(HttpServletRequest request) {
        StringBuffer jb = new StringBuffer();
        String line = null;
        try {
            BufferedReader reader = request.getReader();
            while ((line = reader.readLine()) != null) {
                jb.append(line);
            }
        } catch (Exception e) {
            RoutePutServer.logError(e);
        }

        try {
            RoutePutMessage jsonObject = new RoutePutMessage(jb.toString().trim());
            return jsonObject;
        } catch (JSONException e) {
            RoutePutServer.logError(e);
            return new RoutePutMessage();
        }
    }

    public JSONArray readJSONArrayPOST(HttpServletRequest request) {
        StringBuffer jb = new StringBuffer();
        String line = null;
        try {
            BufferedReader reader = request.getReader();
            while ((line = reader.readLine()) != null) {
                jb.append(line);
            }
        } catch (Exception e) {
            RoutePutServer.logError(e);
        }

        try {
            JSONArray jsonArray = new JSONArray(jb.toString().trim());
            return jsonArray;
        } catch (JSONException e) {
            RoutePutServer.logError(e);
            return new JSONArray();
        }
    }

    public void everySecond()
    {
        long cTime = System.currentTimeMillis();
        ArrayList<RoutePutChannel> idleChannels = new ArrayList<RoutePutChannel>();
        this.lastChannelInteraction.forEach((k,v) -> {
            if ((cTime - v.getTime()) > 300000)
            {
                idleChannels.add(k);
            }
        });
        idleChannels.forEach((c) -> {
            c.removeMember(this);
            this.lastChannelInteraction.remove(c);
        });
        this.pendingOutbound.forEach((k,v) -> {
            if (RoutePutRemoteSession.isChild(this, k) == false)
            {
                this.pendingOutbound.remove(k);
            }
        });
    }

    private synchronized void handleAPIMessage(String remoteIP, RoutePutMessage msg)
    {
        RoutePutChannel channel = msg.getRoutePutChannel();
        this.lastChannelInteraction.put(channel, new Date(System.currentTimeMillis()));
        if (!channel.hasMember(this)) 
        {
            channel.addMember(this);
        }
        String sourceId = msg.getSourceId();
        if (sourceId != null)
        {
            if (!msg.hasTargetId())
            {
                Collection<RoutePutRemoteSession> apiChidren = RoutePutRemoteSession.children(this);
                for (RoutePutRemoteSession s : apiChidren) 
                {
                    this.addPendingOutbound(s.getConnectionId(), msg);
                }
            } else {
                this.addPendingOutbound(msg.getTargetId(), msg);
            }
            if (sourceId.equals(this.getConnectionId()))
            {
                channel.onMessage(this, msg);
            } else {
                boolean sendConnect = false;
                RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSession(sourceId);
                if (remoteSession == null)
                {
                    // This connection doesnt even exist lets create it
                    sendConnect = true;
                } else if (remoteSession.hasParent(this) && !channel.hasMember(remoteSession)) {
                    // This connection exists, and belongs to the api, lets join the channel
                    sendConnect = true;
                }
                if (sendConnect)
                {
                    RoutePutMessage cMsg = new RoutePutMessage();
                    cMsg.setSourceId(sourceId);
                    cMsg.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
                    cMsg.setMetaField("connected", true);
                    cMsg.setMetaField("remoteIP", remoteIP);
                    JSONObject props = new JSONObject();
                    props.put("idleDestruct", msg.getRoutePutMeta().optLong("idleDestruct", 900000));
                    props.put("description", "Virtual Connection for API messages");
                    cMsg.setMetaField("properties", props);
                    cMsg.setChannel(channel);
                    RoutePutRemoteSession.handleRoutedMessage(ApiServlet.this, cMsg);
                }
                RoutePutRemoteSession.handleRoutedMessage(ApiServlet.this, msg);
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse httpServletResponse)
            throws ServletException, IOException {
        httpServletResponse.setContentType("text/javascript");
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        httpServletResponse.setCharacterEncoding("iso-8859-1");
        httpServletResponse.addHeader("Server", "Routeput 1.0");
        String target = request.getPathInfo().replace("+", " ");
        String remoteIP = request.getRemoteAddr();
        if (request.getHeader("X-Real-IP") != null) {
            remoteIP = request.getHeader("X-Real-IP");
        } else if (request.getHeader("X-Forwarded-For") != null) {
            remoteIP = request.getHeader("X-Forwarded-For");
        } else if (request.getHeader("CF-Connecting-IP") != null) {
            remoteIP = request.getHeader("CF-Connecting-IP");
        } else if (request.getHeader("X-Client-IP") != null) {
            remoteIP = request.getHeader("X-Client-IP");
        }
        final String finalRemoteIP = remoteIP;
        // System.err.println("Path: " + target);
        JSONObject response = new JSONObject();
        try {
            String sourceId = ApiServlet.this.getConnectionId();
            if (target.startsWith("/post/")) {
                RoutePutMessage post = readRoutePutMessagePOST(request);
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens()) {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens()) {
                        post.setChannel(RoutePutChannel.getChannel(st.nextToken()));
                    }
                    if (token.equals("id") && st.hasMoreTokens()) {
                        sourceId = st.nextToken();
                    }
                }
                post.setSourceIdIfNull(sourceId);
                // RoutePutServer.logIt("API: " + target + "\n" + post.toString());
                post.setMetaField("apiPost", true);
                RoutePutChannel chan = post.getRoutePutChannel();
                this.rxPackets++;
                handleAPIMessage(finalRemoteIP, post);
                if (post.hasSourceId()) {
                    response.put("sourceId", post.getSourceId());
                    response.put("messages", new JSONArray(this.pendingOutboundFor(post.getSourceId())));
                }
            } else if (target.startsWith("/batch/")) {
                RoutePutChannel channel = null;
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens())
                {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens())
                    {
                        channel = RoutePutChannel.getChannel(st.nextToken());
                    }
                    if (token.equals("id") && st.hasMoreTokens())
                    {
                        sourceId = st.nextToken();
                    }
                }
                final RoutePutChannel finalChannel = channel;
                final String finalSourceId = sourceId;
                JSONArray post = readJSONArrayPOST(request);
                post.forEach((msg) -> {
                    if (msg instanceof JSONObject) {
                        RoutePutMessage rMsg = new RoutePutMessage((JSONObject) msg);
                        rMsg.setChannelIfNull(finalChannel);
                        rMsg.setSourceIdIfNull(finalSourceId);
                        // RoutePutServer.logIt("API: " + target + "\n" + rMsg.toString());
                        rMsg.setMetaField("apiBatch", true);
                        RoutePutChannel chan = rMsg.getRoutePutChannel();
                        this.rxPackets++;
                        handleAPIMessage(finalRemoteIP, rMsg);
                        if (rMsg.hasSourceId()) {
                            response.put(rMsg.getSourceId(), new JSONArray(this.pendingOutboundFor(rMsg.getSourceId())));
                        }
                    }
                });
            }
        } catch (Exception e) {
            RoutePutServer.logError("doPOST API", e);
        }
        httpServletResponse.getWriter().println(response.toString());
    }

    public static boolean isNumber(String strNum) {
        if (strNum == null) {
            return false;
        }
        try {
            double d = Double.parseDouble(strNum);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse)
            throws ServletException, IOException {
        httpServletResponse.setContentType("text/javascript");
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        httpServletResponse.setCharacterEncoding("iso-8859-1");
        httpServletResponse.addHeader("Server", "Routeput 1.0");
        String target = request.getPathInfo().replace("+", " ");
        String remoteIP = request.getRemoteAddr();
        if (request.getHeader("X-Real-IP") != null) {
            remoteIP = request.getHeader("X-Real-IP");
        } else if (request.getHeader("X-Forwarded-For") != null) {
            remoteIP = request.getHeader("X-Forwarded-For");
        } else if (request.getHeader("CF-Connecting-IP") != null) {
            remoteIP = request.getHeader("CF-Connecting-IP");
        } else if (request.getHeader("X-Client-IP") != null) {
            remoteIP = request.getHeader("X-Client-IP");
        }
        final String finalRemoteIP = remoteIP;
        // System.err.println("Path: " + target);
        // RoutePutServer.logIt("API Request: " + target);
        JSONObject response = new JSONObject();
        try {
            if (target.startsWith("/channel/")) {
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens()) {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens()) {
                        String channelName = st.nextToken();
                        RoutePutChannel channel = RoutePutChannel.getChannel(channelName);
                        response = channel.toJSONObject();
                        if (st.hasMoreTokens()) {
                            token = st.nextToken();
                            if ("removeProperty".equals(token) && st.hasMoreTokens()) {
                                token = st.nextToken();
                                channel.removeProperty(this, token);
                                response = channel.toJSONObject();
                            } else if ("properties".equals(token)) {
                                response = channel.getProperties();
                            } else if ("members".equals(token)) {
                                response = channel.membersAsJSONObject();
                            } else if ("setProperty".equals(token)) {
                                RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
                                JSONObject channelProperties = channel.getProperties();
                                request.getParameterMap().forEach((key, value) -> {
                                    if ("true".equals(value[0])) {
                                        rppcm.addUpdate(channel, key, channelProperties.opt(key), true);
                                    } else if ("false".equals(value[0])) {
                                        rppcm.addUpdate(channel, key, channelProperties.opt(key), false);
                                    } else if (isNumber(value[0])) {
                                        rppcm.addUpdate(channel, key, channelProperties.opt(key), Double.valueOf(value[0]));
                                    } else {
                                        rppcm.addUpdate(channel, key, channelProperties.opt(key), value[0]);
                                    }
                                });
                                rppcm.processUpdates(this);
                            } else if ("transmit".equals(token)) {
                                if (response.has("members")) {
                                    response.remove("members");
                                }
                                this.rxPackets++;
                                RoutePutMessage msg = new RoutePutMessage();
                                msg.setChannel(channel);
                                request.getParameterMap().forEach((key, value) -> {
                                    // Fix the type of the parameter value
                                    Object realValue = value[0];
                                    if ("true".equals(value[0])) {
                                        realValue = true;
                                    } else if ("false".equals(value[0])) {
                                        realValue = false;
                                    } else if (isNumber(value[0])) {
                                        realValue = Double.valueOf(value[0]);
                                    }
                                    // Check for special keys
                                    if ("srcId".equals(key)) {
                                        String srcId = value[0];
                                        msg.setSourceId(srcId);
                                    } else if ("dstId".equals(key)) {
                                        msg.setTargetId(value[0]);
                                    } else if ("type".equals(key)) {
                                        msg.setType(value[0]);
                                    } else if ("idleDestruct".equals(key)) {
                                        msg.getRoutePutMeta().put("idleDestruct", Long.valueOf(value[0]).longValue());
                                    } else if (key.startsWith("where_")) {
                                        JSONObject where = msg.getRoutePutMeta().optJSONObject("where");
                                        if (where == null) where = new JSONObject();
                                        where.put(key.substring(6), realValue);
                                        msg.getRoutePutMeta().put("where", where);
                                    } else {
                                        msg.put(key, realValue);
                                    }
                                });
                                if (msg.hasSourceId()) 
                                {
                                    String srcId = msg.getSourceId();
                                    response.put("messages", new JSONArray(this.pendingOutboundFor(srcId)));
                                }
                                msg.setSourceIdIfNull(this.getConnectionId());
                                handleAPIMessage(finalRemoteIP, msg);
                            } else if ("receive".equals(token)) {
                                if (response.has("members")) {
                                    response.remove("members");
                                }
                                if (st.hasMoreTokens()) {
                                    String srcId = st.nextToken();
                                    response.put("messages", new JSONArray(this.pendingOutboundFor(srcId)));
                                }
                            } else if ("blob".equals(token)) {
                                token = st.nextToken();
                                String contentType = BLOBManager.getContentTypeFor(token);
                                httpServletResponse.setContentType(contentType);
                                httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                httpServletResponse.setCharacterEncoding("iso-8859-1");
                                BLOBFile blob = channel.getBLOB(token).get();
                                InputStream inputStream = new FileInputStream(blob);
                                OutputStream output = httpServletResponse.getOutputStream();
                                inputStream.transferTo(output);
                                output.flush();
                                inputStream.close();
                                return;
                            } else if ("blobs".equals(token)) {
                                response.put("blobs", channel.getBlobs());
                            }
                        }
                    }
                }
            } else if ("/channels/".equals(target)) {
                response.put("channels", RoutePutChannel.channelBreakdown());
            } else if ("/channels/stats/".equals(target)) {
                response.put("channels", RoutePutServer.instance.channelStats());
            } else if ("/upstream/".equals(target)) {
                RoutePutChannel channel = RoutePutChannel.getChannel(request.getParameter("channel"));
                String uri = request.getParameter("uri");
                RoutePutSession session = RoutePutChannel.connectUpstream(channel, uri);
                response.put("session", session.toJSONObject());
            } else if ("/remote/sessions/".equals(target)) {
                response.put("remoteSessions", RoutePutRemoteSession.getAllRemoteSessions().stream().map((s) -> {
                    return s.toJSONObject();
                }).collect(Collectors.toList()));
            } else if ("/status/".equals(target)) {
                response.put("status", "ok");
                response.put("version", RoutePutMain.VERSION);
                response.put("uptime", RoutePutMain.getUptime());
            }
        } catch (Exception x) {
            RoutePutServer.logError("doGET API", x);
        }
        httpServletResponse.getWriter().println(response.toString());
        // request.setHandled(true);
    }

    @Override
    public void send(RoutePutMessage jo) 
    {
        // TODO Auto-generated method stub
        this.txPackets++;
        if (jo.hasTargetId()) 
        {
            String targetId = jo.getTargetId();
            this.addPendingOutbound(targetId, jo);
        } else {
            Collection<RoutePutRemoteSession> apiChidren = RoutePutRemoteSession.children(this);
            for (RoutePutSession s : apiChidren) 
            {
                this.addPendingOutbound(s.getConnectionId(), jo);
            }
        }
    }

    protected void addPendingOutbound(String targetId)
    {
        if (targetId != null && !this.pendingOutbound.containsKey(targetId))
        {
            this.pendingOutbound.put(targetId, new LinkedBlockingQueue<RoutePutMessage>());
        }
    }

    protected void addPendingOutbound(String targetId, RoutePutMessage jo)
    {
        if (targetId == null || targetId.isEmpty()) 
            return;
        addPendingOutbound(targetId);
        if (!jo.isType(RoutePutMessage.TYPE_PROPERTY_CHANGE) && 
            !jo.isType(RoutePutMessage.TYPE_PING) && 
            !jo.isType(RoutePutMessage.TYPE_PONG) &&
            !targetId.equals(jo.getSourceId()))
        {
            LinkedBlockingQueue<RoutePutMessage> queue = this.pendingOutbound.get(targetId);
            if (queue.size() > 10000) // Limit the queue size to 10000 messages
                queue.poll();
            queue.add(jo);
        }
    }

    protected Collection<RoutePutMessage> pendingOutboundFor(String targetId)
    {
        if (!this.pendingOutbound.containsKey(targetId)) {
            return Collections.emptyList();
        }
        List<RoutePutMessage> messages = new ArrayList<RoutePutMessage>();
        this.pendingOutbound.get(targetId).drainTo(messages);
        return messages;
    }

    @Override
    public String getConnectionId() 
    {
        // TODO Auto-generated method stub
        return RoutePutChannel.getMasterConnectionId() + "-api";
    }

    @Override
    public RoutePutChannel getDefaultChannel() 
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getRemoteIP() 
    {
        // TODO Auto-generated method stub
        return null;
    }

    public int pendingOutboundCount()
    {
        return this.pendingOutbound.values().stream().mapToInt(Queue::size).sum();
    }

    @Override
    public JSONObject getProperties() 
    {
        this.properties.put("_class", "ApiServlet");
        return this.properties;
    }

    @Override
    public JSONObject toJSONObject() 
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.getConnectionId());
        jo.put("pendingTotal", this.pendingOutboundCount());
        jo.put("pendingByTarget", new JSONObject(this.pendingOutbound.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()))));
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
        jo.put("properties", this.properties);
        return jo;
    }

    @Override
    public boolean isConnected() 
    {
        // TODO Auto-generated method stub
        return RoutePutServer.instance.apiServlet == this;
    }

    @Override
    public boolean isRootConnection() 
    {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public boolean containsConnectionId(String connectionId)
    {
        return RoutePutRemoteSession.isChild(this, connectionId) || this.getConnectionId().equals(connectionId);
    }

    @Override
    public void addMessageListener(RoutePutMessageListener r) 
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void removeMessageListener(RoutePutMessageListener r) 
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) 
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) 
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void firePropertyChange(String key, Object oldValue, Object newValue) 
    {
        // TODO Auto-generated method stub

    }
}
