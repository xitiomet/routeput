package org.openstatic.routeput;

import org.json.*;
import org.openstatic.routeput.client.RoutePutClient;

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
    protected LinkedHashMap<String, RoutePutServerWebsocket> sessions;
    protected JSONObject settings;
    protected static RoutePutServer instance;
    private Thread mainThread;
    private boolean keep_running;
    public RoutePutChannel routeputDebug;
    public File channelRoot;

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
        this.settings = settings;
        this.channelRoot = new File(settings.optString("channelRoot", "./channel/"));
        RoutePutChannel.setChannelRoot(this.channelRoot);

        this.routeputDebug = RoutePutChannel.getChannel("routeputDebug");
        this.routeputDebug.setPermanent(true);
        this.sessions = new LinkedHashMap<String, RoutePutServerWebsocket>();
        httpServer = new Server(settings.optInt("port", 6144));
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
        client.setProperty("upstream", uri);
        client.connect();
        return client;
    }
    
    public void everySecond(int tick) throws Exception
    {
        if (this.routeputDebug != null)
        {
            RoutePutMessage jo = new RoutePutMessage();
            jo.put("channelStats", this.channelStats());
            jo.setChannel(this.routeputDebug);
            this.routeputDebug.broadcast(jo);
        } else {
            System.err.println("routeputDebug is null");
        }
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
            if (chan.getProperties().has("rssi"))
            {
                int signal = 0;
                signal = 120 - Math.abs(chan.getProperties().optInt("rssi", -120));
                if (signal < 0) signal = 0;
                if (signal > 100) signal = 100;
                js.put("signal", signal);
            }
            if (chan.hasCollector())
                js.put("collector", chan.getCollector().getConnectionId());
            
            jo.put(dChan, js);
        }
        return jo;
    }
    
    public static void logIt(String text)
    {
        System.err.println(text);
        if (RoutePutServer.instance != null)
        {
            if (RoutePutServer.instance.routeputDebug != null)
            {
                RoutePutMessage l = new RoutePutMessage();
                l.setChannel("routeputDebug");
                l.put("logIt",  text);
                RoutePutServer.instance.routeputDebug.handleMessage(null, l);
            }
        }
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
        } catch (Exception e2) {
            System.err.println("Logging Exception");
            e2.printStackTrace(System.err);
        }
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
