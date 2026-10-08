package org.openstatic.routeput;

import org.json.*;
import org.openstatic.routeput.util.POSTManager;

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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

public class ApiServlet extends HttpServlet implements RoutePutSession 
{
    private JSONObject properties;
    private long rxPackets;
    private long txPackets;
    private Map<RoutePutChannel, Date> lastChannelInteraction;
    private Map<String, Map<RoutePutChannel, LinkedBlockingQueue<RoutePutMessage>>> pendingOutbound;

    public ApiServlet() 
    {
        this.properties = new JSONObject();
        this.properties.put("description", "Virtual Session for API GET/POST messages");
        this.rxPackets = 0;
        this.txPackets = 0;
        RoutePutServer.logIt("** API SERVLET INITIALIZED **");
        RoutePutServer.instance.apiServlet = this;
        this.lastChannelInteraction = new HashMap<RoutePutChannel, Date>();
        this.lastChannelInteraction = Collections.synchronizedMap(this.lastChannelInteraction);
        this.pendingOutbound = new HashMap<String, Map<RoutePutChannel, LinkedBlockingQueue<RoutePutMessage>>>();
        //this.pendingOutbound = Collections.synchronizedMap(this.pendingOutbound);
    }

    public RoutePutMessage readRoutePutMessagePOST(HttpServletRequest request)
    {
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

    public JSONArray readJSONArrayPOST(HttpServletRequest request)
    {
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

    // Read the raw POST body as bytes (used for file uploads to /blob/).
    public byte[] readBytesPOST(HttpServletRequest request)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = request.getInputStream())
        {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                baos.write(buffer, 0, read);
            }
        } catch (Exception e) {
            RoutePutServer.logError(e);
        }
        return baos.toByteArray();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String md5Hex(byte[] bytes)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++)
            {
                int v = digest[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    // Pull filename="..." out of a Content-Disposition header, or null if absent.
    private static String filenameFromContentDisposition(String header)
    {
        if (header == null) return null;
        int idx = header.toLowerCase().indexOf("filename=");
        if (idx < 0) return null;
        String value = header.substring(idx + "filename=".length()).trim();
        if (value.startsWith("\""))
        {
            int end = value.indexOf('"', 1);
            if (end > 0) value = value.substring(1, end);
        } else {
            int semi = value.indexOf(';');
            if (semi > 0) value = value.substring(0, semi).trim();
        }
        return value.isEmpty() ? null : value;
    }

    // Chunk a locally stored blob out to every channel member as if sent by srcId.
    private void transmitBlobToChannel(RoutePutChannel channel, String name, String srcId, StringBuffer sb)
    {
        int size = sb.length();
        int chunkSize = 4096;
        int numChunks = (size + chunkSize - 1) / chunkSize;
        String masterConnectionId = RoutePutChannel.getMasterConnectionId();
        for (int i = 0; i < numChunks; i++)
        {
            RoutePutMessage mm = new RoutePutMessage();
            mm.setType(RoutePutMessage.TYPE_BLOB);
            mm.setSourceId(srcId);
            mm.setChannel(channel);
            mm.setMetaField("name", name);
            mm.setMetaField("i", i + 1);
            mm.setMetaField("of", numChunks);
            mm.appendHop(masterConnectionId);
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, size);
            mm.setMetaField("data", sb.substring(start, end));
            channel.broadcast(mm);
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
            boolean hasChildInChannel = RoutePutRemoteSession.children(this).stream()
                .anyMatch((child) -> c.hasMember(child));
            if (!hasChildInChannel)
            {
                c.removeMember(this);
                this.lastChannelInteraction.remove(c);
            }
        });
        // removeIf mutates the backing map safely; forEach + remove throws CME
        this.pendingOutbound.keySet().removeIf((k) -> RoutePutRemoteSession.isChild(this, k) == false);
    }

    private synchronized void handleAPIMessage(String remoteIP, RoutePutMessage msg)
    {
        RoutePutChannel channel = msg.getRoutePutChannel();
        if (!channel.hasMember(this)) 
        {
            this.lastChannelInteraction.put(channel, new Date(System.currentTimeMillis()));
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
                startRemoteConnection(channel, sourceId, remoteIP, msg.getRoutePutMeta());
                RoutePutRemoteSession.handleRoutedMessage(ApiServlet.this, msg);
            }
        }
    }

    // for initiating an api only connection, this ill create a virtual connection if it doesn't already exist
    private void startRemoteConnection(RoutePutChannel channel, String sourceId, String remoteIP, JSONObject options)
    {
        if (channel == null || sourceId == null)
            return;
        if (!channel.hasMember(this)) 
        {
            channel.addMember(this);
        }
        this.lastChannelInteraction.put(channel, new Date(System.currentTimeMillis()));
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
            long idleDestruct = options.optLong("idleDestruct", 900000);
            if (idleDestruct < 30000)
                idleDestruct = 30000;
            props.put("_idleDestruct", idleDestruct);
            if (options.has("receiveUrl"))
                props.put("_receiveUrl", options.getString("receiveUrl"));
            cMsg.setMetaField("properties", props);
            cMsg.setChannel(channel);
            RoutePutRemoteSession.handleRoutedMessage(ApiServlet.this, cMsg);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse httpServletResponse)
            throws ServletException, IOException {
        httpServletResponse.setContentType("text/javascript");
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        httpServletResponse.setCharacterEncoding("iso-8859-1");
        httpServletResponse.addHeader("Server", "Routeput " + RoutePutMain.VERSION);
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
            if (target.startsWith("/post/"))
            {
                RoutePutMessage post = readRoutePutMessagePOST(request);
                StringTokenizer st = new StringTokenizer(target, "/");
                boolean blind = false;
                while (st.hasMoreTokens())
                {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens()) {
                        post.setChannel(RoutePutChannel.getChannel(st.nextToken()));
                    }
                    if (token.equals("id") && st.hasMoreTokens()) {
                        sourceId = st.nextToken();
                    }
                    if (token.equals("blind")) {
                        blind = true;
                    }
                }
                post.setSourceIdIfNull(sourceId);
                // RoutePutServer.logIt("API: " + target + "\n" + post.toString());
                post.setMetaField("apiPost", true);
                RoutePutChannel chan = post.getRoutePutChannel();
                this.rxPackets++;
                handleAPIMessage(finalRemoteIP, post);
                if (post.hasSourceId()) 
                {
                    response.put("sourceId", post.getSourceId());
                    RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSession(post.getSourceId());
                    if (remoteSession != null) 
                    {
                        if (remoteSession.getProperties().optBoolean("_receiveManaged", false)) 
                        {
                            blind = true;
                        }
                    }
                    if (!blind) {
                        response.put("messages", new JSONArray(this.pendingOutboundFor(post.getSourceId(), chan)));
                    }
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
                            response.put(rMsg.getSourceId(), new JSONArray(this.pendingOutboundFor(rMsg.getSourceId(), chan)));
                        }
                    }
                });
            } else if (target.startsWith("/blob/")) {
                RoutePutChannel channel = null;
                String blobName = null;
                // Blobs carry the server's master connectionId as srcId unless /id/ overrides it.
                String blobSrcId = RoutePutChannel.getMasterConnectionId();
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens())
                {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens()) {
                        channel = RoutePutChannel.getChannel(st.nextToken());
                    } else if (token.equals("id") && st.hasMoreTokens()) {
                        blobSrcId = st.nextToken();
                    } else if (token.equals("name") && st.hasMoreTokens()) {
                        blobName = st.nextToken();
                    }
                }
                byte[] bytes = readBytesPOST(request);
                String contentType = request.getContentType();
                if (contentType != null && contentType.indexOf(';') > -1) {
                    contentType = contentType.substring(0, contentType.indexOf(';')).trim();
                }
                if (blobName == null) {
                    blobName = request.getParameter("name");
                }
                if (blobName == null) {
                    blobName = filenameFromContentDisposition(request.getHeader("Content-Disposition"));
                }
                if (blobName == null) {
                    String ext = "bin";
                    if (contentType != null && contentType.indexOf('/') > -1) {
                        String sub = contentType.substring(contentType.indexOf('/') + 1).replaceAll("[^A-Za-z0-9]", "");
                        if (!sub.isEmpty()) ext = sub;
                    }
                    blobName = md5Hex(bytes) + "." + ext;
                }
                // Strip any path components so a crafted name can't escape the blob folder.
                blobName = new File(blobName).getName();
                if (channel == null) {
                    response.put("error", "a channel is required, e.g. /blob/channel/<name>/");
                } else if (bytes.length == 0) {
                    response.put("error", "request body was empty");
                } else if (!BLOBManager.isInitialized()) {
                    response.put("error", "blob storage is not enabled on this server");
                } else {
                    File blobFolder = channel.getBlobFolder();
                    BLOBFile blobFile = new BLOBFile(blobFolder, channel.getName(), blobName);
                    try (FileOutputStream fos = new FileOutputStream(blobFile)) {
                        fos.write(bytes);
                    }
                    this.rxPackets++;
                    // /id/ sets the srcId the blob appears to come from; defaults to this api session.
                    transmitBlobToChannel(channel, blobFile.getName(), sourceId, blobFile.getBase64StringBuffer());
                    response.put("srcId", sourceId);
                    response.put("blob", blobFile.toJSONObject());
                }
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
        httpServletResponse.addHeader("Server", "Routeput " + RoutePutMain.VERSION);
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
            if (target.startsWith("/channel/")) 
            {
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens()) {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens()) {
                        String channelName = st.nextToken();
                        RoutePutChannel channel = RoutePutChannel.getChannel(channelName);
                        response = channel.toJSONObject();
                        
                        if (st.hasMoreTokens()) 
                        {
                            if (response.has("members")) {
                                response.remove("members");
                            }
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
                                AtomicBoolean blind = new AtomicBoolean(false);
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
                                    } else if ("blind".equals(key)) {
                                        blind.set(true);
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
                                    RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSession(srcId);
                                    if (remoteSession != null)
                                    {
                                        if (remoteSession.getProperties().optBoolean("_receiveManaged", false))
                                        {
                                            blind.set(true);
                                        }
                                    }
                                    if (!blind.get())
                                        response.put("messages", new JSONArray(this.pendingOutboundFor(srcId, channel)));
                                }
                                msg.setSourceIdIfNull(this.getConnectionId());
                                handleAPIMessage(finalRemoteIP, msg);
                            } else if ("receive".equals(token)) {
                                if (st.hasMoreTokens()) 
                                {
                                    String srcId = st.nextToken();
                                    this.lastChannelInteraction.put(channel, new Date(System.currentTimeMillis()));
                                    long idleDestruct = 900000;
                                    if (request.getParameter("idleDestruct") != null)
                                    {
                                        idleDestruct = Long.parseLong(request.getParameter("idleDestruct"));
                                    }
                                    startRemoteConnection(channel, srcId, remoteIP, (new JSONObject().put("idleDestruct", idleDestruct)));
                                    response.put("messages", new JSONArray(this.pendingOutboundFor(srcId, channel)));
                                    RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSessionFor(this, srcId);
                                    if (remoteSession != null)
                                    {
                                        remoteSession.getProperties().put("_receiveManaged", true);
                                    }
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

    private boolean handleReceiveUrl(final RoutePutRemoteSession remoteSession, final RoutePutMessage jo)
    {
        boolean handled = false;
        if (remoteSession != null) 
        {
            JSONObject props = remoteSession.getProperties();
            if (props.has("_receiveUrl")) 
            {
                String receiveUrl = props.getString("_receiveUrl");
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("messages", new JSONArray().put(jo));
                jsonObject.put("timestamp", System.currentTimeMillis());
                jsonObject.put("session", remoteSession.toJSONObject());
                CompletableFuture<POSTManager.Response> future = POSTManager.queuePost(receiveUrl, jsonObject);
                future.thenAccept((response) -> {
                    if (response != null && response.isSuccessful()) 
                    {
                        RoutePutMain.log("info", "Posted to URL: " + receiveUrl + " for session: " + remoteSession.getConnectionId());
                        // Handle successful response if needed
                        String contentType = response.getContentType();
                        String body = response.getBody();
                        if (contentType != null && (contentType.equals("text/json") || contentType.equals("application/json") || contentType.equals("text/javascript") || contentType.equals("application/javascript"))) 
                        {
                            try
                            {
                                JSONObject jsonResponse = new JSONObject(body);
                                if (jsonResponse.has("messages")) 
                                {
                                    JSONArray messages = jsonResponse.getJSONArray("messages");
                                    for (int i = 0; i < messages.length(); i++) 
                                    {
                                        JSONObject message = messages.getJSONObject(i);
                                        RoutePutMessage routeputMessage = new RoutePutMessage(message);
                                        routeputMessage.setChannelIfNull(jo.getRoutePutChannel());
                                        routeputMessage.setSourceIdIfNull(jo.getSourceId());
                                        RoutePutRemoteSession.handleRoutedMessage(ApiServlet.this, routeputMessage);
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }
                });
                
                handled = true;
            }
        }
        return handled;
    }

    @Override
    public void send(RoutePutMessage jo) 
    {
        this.txPackets++;
        if (!jo.isType(RoutePutMessage.TYPE_PROPERTY_CHANGE) && 
                !jo.isType(RoutePutMessage.TYPE_PING) && 
                !jo.isType(RoutePutMessage.TYPE_PONG) && 
                !jo.isType(RoutePutMessage.TYPE_MIDI) && 
                !jo.isType(RoutePutMessage.TYPE_PULSE) &&
                !jo.isType(RoutePutMessage.TYPE_BLOB))
        {
            if (jo.hasTargetId()) 
            {
                String targetId = jo.getTargetId();
                RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSessionFor(this, targetId);
                this.addPendingOutbound(targetId, jo);
            } else {
                Collection<RoutePutRemoteSession> apiChidren = RoutePutRemoteSession.children(this);
                for (RoutePutSession s : apiChidren) 
                {
                    this.addPendingOutbound(s.getConnectionId(), jo);
                }
            }
        }
        
    }

    // Create a queue if needed for passing messages to GET/POST clients
    protected void addPendingOutbound(String targetId, RoutePutChannel channel)
    {
        if (targetId != null && !this.pendingOutbound.containsKey(targetId))
        {
            this.pendingOutbound.put(targetId, new HashMap<RoutePutChannel, LinkedBlockingQueue<RoutePutMessage>>());
        }
        if (targetId != null && !this.pendingOutbound.get(targetId).containsKey(channel))
        {
            this.pendingOutbound.get(targetId).put(channel, new LinkedBlockingQueue<RoutePutMessage>());
        }
    }

    // Create a queue if needed for passing messages to GET/POST clients
    // and send a message to that queue
    protected void addPendingOutbound(String targetId, RoutePutMessage jo)
    {
        if (targetId == null || targetId.isEmpty()) 
            return;
        if (targetId.equals(this.getConnectionId()))
            return;
        RoutePutRemoteSession remoteSession = RoutePutRemoteSession.findRemoteSessionFor(this, targetId);
        boolean handled = handleReceiveUrl(remoteSession, jo);
        if (handled)
            return;
        RoutePutChannel chan = jo.getRoutePutChannel();
        if (chan.hasMember(targetId))
        {
            addPendingOutbound(targetId, jo.getRoutePutChannel());
            if (!targetId.equals(jo.getSourceId()))
            {
                LinkedBlockingQueue<RoutePutMessage> queue = this.pendingOutbound.get(targetId).get(jo.getRoutePutChannel());
                if (queue.size() > 10000) // Limit the queue size to 10000 messages
                    queue.poll();
                queue.add(jo);
            }
        }
    }

    protected Collection<RoutePutMessage> pendingOutboundFor(String targetId, RoutePutChannel channel)
    {
        if (!this.pendingOutbound.containsKey(targetId) || !this.pendingOutbound.get(targetId).containsKey(channel)) {
            return Collections.emptyList();
        }
        List<RoutePutMessage> messages = new ArrayList<RoutePutMessage>();
        this.pendingOutbound.get(targetId).get(channel).drainTo(messages);
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
        return null;
    }

    @Override
    public String getRemoteIP() 
    {
        return null;
    }

    public int pendingOutboundCount()
    {
        return this.pendingOutbound.values().stream().mapToInt(m -> m.values().stream().mapToInt(Queue::size).sum()).sum();
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
        jo.put("pendingByTargetAndChannel", new JSONObject(this.pendingOutbound.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, v -> v.getValue().size()))))));
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
