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
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;

public class ApiServlet extends HttpServlet implements RoutePutSession {
    private JSONObject properties;
    private long rxPackets;
    private long txPackets;
    private Map<RoutePutChannel, Date> lastChannelInteraction;

    public ApiServlet() {
        this.properties = new JSONObject();
        this.properties.put("description", "Virtual Session for API GET/POST messages");
        this.rxPackets = 0;
        this.txPackets = 0;
        RoutePutServer.logIt("** API SERVLET INITIALIZED **");
        RoutePutServer.instance.apiServlet = this;
        this.lastChannelInteraction = new HashMap<RoutePutChannel, Date>();
        this.lastChannelInteraction = Collections.synchronizedMap(this.lastChannelInteraction);
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
    }

    private synchronized void handleAPIMessage(String remoteIP, RoutePutMessage msg) {
        RoutePutChannel channel = msg.getRoutePutChannel();
        this.lastChannelInteraction.put(channel, new Date(System.currentTimeMillis()));
        String sourceId = msg.getSourceId();
        if (sourceId != null) {
            if (sourceId.equals(this.getConnectionId())) {
                if (!channel.hasMember(this)) {
                    channel.addMember(this);
                }
                channel.onMessage(this, msg);
            } else {
                boolean sendConnect = false;
                RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSession(sourceId);
                if (remoteSession == null) {
                    // This connection doesnt even exist lets create it
                    sendConnect = true;
                } else if (remoteSession.hasParent(this) && !channel.hasMember(remoteSession)) {
                    // This connection exists, and belongs to the api, lets join the channel
                    sendConnect = true;
                }
                if (sendConnect) {
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
                handleAPIMessage(remoteIP, post);
            } else if (target.startsWith("/batch/")) {
                RoutePutChannel channel = null;
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens()) {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens()) {
                        channel = RoutePutChannel.getChannel(st.nextToken());
                    }
                    if (token.equals("id") && st.hasMoreTokens()) {
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
                        handleAPIMessage(remoteIP, rMsg);
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
                                        msg.setSourceId(value[0]);
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
                                msg.setSourceIdIfNull(this.getConnectionId());
                                handleAPIMessage(remoteIP, msg);
                            } else if ("blob".equals(token)) {
                                token = st.nextToken();
                                String contentType = BLOBManager.getContentTypeFor(token);
                                httpServletResponse.setContentType(contentType);
                                httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                httpServletResponse.setCharacterEncoding("iso-8859-1");
                                InputStream inputStream = new FileInputStream(new File(channel.getBlobFolder(), token));
                                OutputStream output = httpServletResponse.getOutputStream();
                                inputStream.transferTo(output);
                                output.flush();
                                inputStream.close();
                                return;
                            }
                        }
                    }
                }
            } else if (target.startsWith("/blob/")) {
                StringTokenizer st = new StringTokenizer(target, "/");
                String context = null;
                String token = st.nextToken();
                File blobContext = BLOBManager.getBlobRoot();
                while (blobContext.isDirectory() && st.hasMoreTokens())
                {
                    token = st.nextToken();
                    blobContext = new File(blobContext, token);
                }
                if (blobContext.isDirectory())
                {
                    if (!"blob".equals(token))
                    {
                        context = token;
                    }
                    JSONArray ja = new JSONArray();
                    ja = new JSONArray();
                    String[] names = blobContext.list();
                    for (int i = 0; i < names.length; i++)
                    {
                        BLOBFile file = new BLOBFile(blobContext, context, names[i]);
                        ja.put(file.toJSONObject());
                    }
                    response.put(token, ja);
                } else {
                    String contentType = BLOBManager.getContentTypeFor(token);
                    httpServletResponse.setContentType(contentType);
                    httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                    httpServletResponse.setCharacterEncoding("iso-8859-1");
                    InputStream inputStream = new FileInputStream(blobContext);
                    OutputStream output = httpServletResponse.getOutputStream();
                    inputStream.transferTo(output);
                    output.flush();
                    inputStream.close();
                    return;
                }
            } else if ("/channels/".equals(target)) {
                response.put("channels", RoutePutChannel.channelBreakdown());
            } else if ("/channels/stats/".equals(target)) {
                response.put("channels", RoutePutServer.instance.channelStats());
            } else if ("/upstream/".equals(target)) {
                RoutePutChannel channel = RoutePutChannel.getChannel(request.getParameter("channel"));
                String uri = request.getParameter("uri");
                RoutePutSession session = RoutePutServer.instance.connectUpstream(channel, uri);
                response.put("session", session.toJSONObject());
            }
        } catch (Exception x) {
            RoutePutServer.logError("doGET API", x);
        }
        httpServletResponse.getWriter().println(response.toString());
        // request.setHandled(true);
    }

    @Override
    public void send(RoutePutMessage jo) {
        // TODO Auto-generated method stub
        this.txPackets++;
    }

    @Override
    public String getConnectionId() {
        // TODO Auto-generated method stub
        return "api-" + RoutePutChannel.getHostname();
    }

    @Override
    public RoutePutChannel getDefaultChannel() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getRemoteIP() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public JSONObject getProperties() {
        this.properties.put("_class", "ApiServlet");
        return this.properties;
    }

    @Override
    public JSONObject toJSONObject() {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.getConnectionId());
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
    public boolean isConnected() {
        // TODO Auto-generated method stub
        return RoutePutServer.instance.apiServlet == this;
    }

    @Override
    public boolean isRootConnection() {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public boolean containsConnectionId(String connectionId) {
        return RoutePutRemoteSession.isChild(this, connectionId) || this.getConnectionId().equals(connectionId);
    }

    @Override
    public void addMessageListener(RoutePutMessageListener r) {
        // TODO Auto-generated method stub
    }

    @Override
    public void removeMessageListener(RoutePutMessageListener r) {
        // TODO Auto-generated method stub
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void firePropertyChange(String key, Object oldValue, Object newValue) {
        // TODO Auto-generated method stub

    }
}
