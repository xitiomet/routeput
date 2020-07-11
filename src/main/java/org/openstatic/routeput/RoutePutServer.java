package org.openstatic.routeput;

import org.json.*;
import org.openstatic.routeput.client.RoutePutClient;
import org.openstatic.routeput.client.RoutePutSessionListener;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import java.util.ArrayList;
import java.util.Random;
import java.util.LinkedHashMap;
import java.util.EnumSet;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class RoutePutServer implements Runnable
{
    private Server httpServer;
    protected LinkedHashMap<String, RoutePutSession> sessions;
    protected JSONObject settings;
    protected static RoutePutServer instance;
    private Thread mainThread;
    private boolean keep_running;
    public String hostname;
    public RoutePutChannel routeputDebug;

    public static class HeaderAddingFilter implements Filter
    {
        public HeaderAddingFilter() {}
        
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
                if (response instanceof HttpServletResponse)
                {
                    HttpServletResponse httpResponse = (HttpServletResponse) response;
                    httpResponse.addHeader("Server", "Routeput 1.0");
                }
                chain.doFilter(request, response);
        }

        @Override
        public void init(FilterConfig arg0) throws ServletException {

        }

        @Override
        public void destroy() {}
    }

    
    public static synchronized String generateBigAlphaKey(int key_length)
    {
        try
        {
            // make sure we never get the same millis!
            Thread.sleep(1);
        } catch (Exception e) {}
        Random n = new Random(System.currentTimeMillis());
        String alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuffer return_key = new StringBuffer();
        for (int i = 0; i < key_length; i++)
        {
            return_key.append(alpha.charAt(n.nextInt(alpha.length())));
        }
        String randKey = return_key.toString();
        //System.err.println("Generated Rule ID: " + randKey);
        return randKey;
    }
    
    public RoutePutServer(JSONObject settings)
    {
        RoutePutServer.instance = this;
        InetAddress ip;
        try 
        {
            ip = InetAddress.getLocalHost();
            this.hostname = ip.getHostName();
        } catch (Exception e) {}
        this.settings = settings;
        this.routeputDebug = RoutePutChannel.getChannel("routeputDebug");
        this.sessions = new LinkedHashMap<String, RoutePutSession>();
        httpServer = new Server(settings.optInt("port", 6144));
        BLOBManager.setRoot(new File(settings.optString("blobRoot", "./blob/")));
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.addFilter(HeaderAddingFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.setContextPath("/");
        context.addServlet(ApiServlet.class, "/api/*");
        context.addServlet(EventsWebSocketServlet.class, settings.optString("channelPath", "/channel/*"));
        context.addServlet(InterfaceServlet.class, "/*");
        httpServer.setHandler(context);
        this.mainThread = new Thread(this);
        this.mainThread.setDaemon(true);
        this.mainThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread() 
        { 
            public void run() 
            { 
                RoutePutServer.instance.keep_running = false;
            } 
        });
        connectUpstreams();
    }

    public void connectUpstreams()
    {
        JSONArray upstreams = this.settings.optJSONArray("upstreams");
        if (upstreams != null)
        {
            upstreams.forEach((o) -> {
                if (o instanceof JSONObject)
                {
                    JSONObject jo = (JSONObject) o;
                    connectUpstream(RoutePutChannel.getChannel(jo.optString("channel","*")), jo.optString("uri", null));
                }
            });
        }
    }
    
    public void run()
    {
        this.keep_running = true;
        int tick = 0;
        while(this.keep_running)
        {
            try
            {
                everySecond(tick);
                tick++;
                if (tick >= 60) tick = 0;
                Thread.sleep(1000);
            } catch (Exception e) {
                logIt(e);
            }
        }
    }

    public RoutePutSession connectUpstream(RoutePutChannel channel, String uri)
    {
        final RoutePutClient client = new RoutePutClient(channel, uri);
        client.addSessionListener(new RoutePutSessionListener() {
            
            @Override
            public void onConnect(RoutePutSession session, boolean local) 
            {
                RoutePutServer.this.sessions.put(session.getConnectionId(), session);
                session.addMessageListener(new RoutePutMessageListener() {
                    @Override
                    public void onMessage(RoutePutMessage message) {
                        RoutePutServer.this.handleIncomingEvent(message, session);
                    }
                });
            }
        
            @Override
            public void onClose(RoutePutSession session, boolean local)
            {
                String connectionId = session.getConnectionId();
                if (RoutePutServer.this.sessions.containsKey(connectionId))
                    RoutePutServer.this.sessions.remove(connectionId);                
            }
        });
        client.setProperty("upstream", uri);
        client.connect();
        return client;
    }
    
    public void everySecond(int tick) throws Exception
    {
        RoutePutMessage jo = new RoutePutMessage();
        jo.put("channelStats", this.channelStats());
        jo.setChannel("routeputDebug");
        this.handleIncomingEvent(jo, null);
        if (tick % settings.optInt("pingPongSecs", 20) == 0)
        {
            RoutePutServer.this.sessions.values().parallelStream().forEach((s) -> {
                if (s instanceof RoutePutServerWebsocket)
                {
                    RoutePutServerWebsocket sws = (RoutePutServerWebsocket) s;
                    sws.ping();
                }
            });
        }
    }
    
    public void handleIncomingEvent(RoutePutMessage j, RoutePutSession session)
    {
        j.appendMetaArray("hops", this.hostname);
        String eventChannel = j.getChannel();
        RoutePutChannel channel = j.getRoutePutChannel();

        if (j.hasMetaField("setChannelProperty"))
        {
            JSONObject storeRequest = j.getRoutePutMeta().optJSONObject("setChannelProperty");
            for(String k : storeRequest.keySet())
            {
                String v = storeRequest.getString(k);
                channel.setProperty(k, j.getPathValue(v));
            }
        }
        if (j.hasMetaField("setSessionProperty") && session != null)
        {
            JSONObject storeRequest = j.getRoutePutMeta().optJSONObject("setSessionProperty");
            for(String k : storeRequest.keySet())
            {
                String v = storeRequest.getString(k);
                session.getProperties().put(k, j.getPathValue(v));
            }
        }
        
        if (channel.hasCollector())
        {
            // This Channel has a connected collector
            RoutePutSession collector = channel.getCollector();
            if (session == collector)
            {
                //this connection is that collector!
                if (j.hasTargetId())
                {
                    RoutePutSession target = findSessionById(j.getTargetId());
                    if (target != null)
                    {
                        j.setMetaField("collectorTargeted", true);
                        target.send(j);
                    }
                } else {
                    j.setMetaField("collectorBroadcast", true);
                    broadcast(eventChannel, j);
                }
            } else {
                // absorb all packets into collector
                j.setMetaField("collectorAbsorbed", true);
                collector.send(j);
            }
        } else {
            // This Channel is a complete free for all!
            if (j.hasTargetId())
            {
                // Ok this packet has a target in the channel
                RoutePutSession target = findSessionById(j.getTargetId());
                if (target != null)
                   target.send(j);
            } else {
                // Everybody but the sender should get this packet
                broadcast(eventChannel, j);
            }
        }
    }
    
    public void setState(boolean b)
    {
        if (b)
        {
            try
            {
                httpServer.start();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        } else {
            try
            {
                httpServer.stop();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }
    
    public RoutePutSession findSessionById(String id)
    {
        if (this.sessions.containsKey(id))
        {
            return this.sessions.get(id);
        }
        return null;
    }
    
    public JSONObject channelStats()
    {
        JSONObject jo = new JSONObject();
        for(RoutePutChannel chan : RoutePutChannel.getChannels())
        {
            String dChan = chan.getName();
            JSONObject js = new JSONObject();
            js.put("rx", chan.getMessagesRxPerSecond());
            js.put("tx", chan.getMessagesTxPerSecond());
            js.put("members", chan.memberCount());
            if (chan.hasCollector())
                js.put("collector", chan.getCollector().getConnectionId());
            
            jo.put(dChan, js);
        }
        return jo;
    }
    
    public JSONObject channelBreakdown(String channel)
    {
        JSONObject jo = new JSONObject();
        for(RoutePutSession s : this.sessions.values())
        {
            if (s.subscribedTo(channel))
            {
                jo.put(s.getConnectionId(), s.toJSONObject());
            }
        }
        return jo;
    }

    public JSONArray channelMembers(String channel)
    {
        JSONArray ja = new JSONArray();
        for(RoutePutSession s : this.sessions.values())
        {
            if (s.subscribedTo(channel))
            {
                ja.put(s.getConnectionId());
            }
        }
        return ja;
    }

    public void broadcast(String eventChannel, RoutePutMessage jo)
    {
        boolean showBroadcasts = (routeputDebug.getProperties().optBoolean("showBroadcasts", false) && !"routeputDebug".equals(eventChannel));
        if (showBroadcasts)
        {
            System.err.println("Broadcast (" + eventChannel + "): " + jo.toString());
        }
        this.sessions.values().parallelStream().forEach((s) ->
        {
            if (s.subscribedTo(eventChannel) && s.isRootConnection())
            {
                if (showBroadcasts)
                    System.err.println("   ----Root Connection " + s.getConnectionId());
                try
                {
                    // Never Send the event to the creator or its relay
                    if (!s.containsConnectionId(jo.getSourceId()))
                    {
                        s.send(jo);
                        if (showBroadcasts)
                            System.err.println("   --------SENT  " + s.getConnectionId());
                    }
                } catch (Exception e) {
                    logIt(e);
                }
            } else {
                if (showBroadcasts)
                    System.err.println("   ----Skipping Connection " + s.getConnectionId());
            }
        });
    }
    
    public static void logIt(String text)
    {
        System.err.println(text);
        RoutePutMessage l = new RoutePutMessage();
        l.setChannel("routeputDebug");
        l.put("logIt",  text);
        RoutePutServer.instance.handleIncomingEvent(l, null);
    }

    public static void logIt(Exception e)
    {
        logIt("NADA", e);
    }
    
    public static void logIt(String info, Exception e)
    {
        logIt("(" +info+ ") Exception - " + e.toString());
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            e.printStackTrace(ps);
            logIt(baos.toString());
        } catch (Exception e2) {}
    }

    
    public static JSONObject loadJSONObject(File file)
    {
        try
        {
            FileInputStream fis = new FileInputStream(file);
            StringBuilder builder = new StringBuilder();
            int ch;
            while((ch = fis.read()) != -1){
                builder.append((char)ch);
            }
            fis.close();
            JSONObject props = new JSONObject(builder.toString());
            return props;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveJSONObject(File file, JSONObject obj)
    {
        try
        {
            FileOutputStream fos = new FileOutputStream(file);
            PrintStream ps = new PrintStream(fos);
            ps.print(obj.toString());
            ps.close();
            fos.close();
        } catch (Exception e) {
            logIt(e);
        }
    }
    
}
