package org.openstatic;

import org.json.*;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;

import java.net.InetAddress;
import java.net.URL;
import java.net.URI;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Vector;
import java.util.Random;
import java.util.LinkedHashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;    

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;

import org.apache.commons.cli.*;

public class RoutePutServer implements Runnable
{
    private Server httpServer;
    protected ArrayList<RoutePutSession> sessions;
    protected LinkedHashMap<String, RoutePutSession> collectors;
    protected JSONObject settings;
    protected static RoutePutServer instance;
    private Thread mainThread;
    private boolean keep_running;

    public static void main(String[] args)
    {
        CommandLine cmd = null;
        JSONObject settings = new JSONObject();
        try
        {
            Options options = new Options();
            CommandLineParser parser = new DefaultParser();
            options.addOption(new Option("c", "config", true, "Config file location"));
            options.addOption(new Option("p", "port", true, "Specify HTTP port"));
            options.addOption(new Option("?", "help", false, "Shows help"));
            cmd = parser.parse(options, args);
            
            if (cmd.hasOption("?"))
            {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp( "routeput", options );
                System.exit(0);
            }
            
            if (cmd.hasOption("c"))
            {
                File config = new File(cmd.getOptionValue('c',"routeput.json"));
                settings = loadJSONObject(config);
            }
            
            if (cmd.hasOption("p"))
            {
                int port = Integer.valueOf(cmd.getOptionValue('p',"6144")).intValue();
                settings.put("port", port);
            }
            
            RoutePutServer rps = new RoutePutServer(settings);
            rps.setState(true);
            
            Runtime.getRuntime().addShutdownHook(new Thread() 
            { 
              public void run() 
              { 
                RoutePutServer.instance.keep_running = false;
              } 
            }); 
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        
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
        this.sessions = new ArrayList<RoutePutSession>();
        this.collectors = new LinkedHashMap<String, RoutePutSession>();
        httpServer = new Server(settings.optInt("port", 6144));
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(ApiServlet.class, "/api/*");
        context.addServlet(EventsWebSocketServlet.class, "/channel/*");
        context.addServlet(InterfaceServlet.class, "/*");
        httpServer.setHandler(context);
        this.mainThread = new Thread(this);
        this.mainThread.setDaemon(true);
        this.mainThread.start();
    }
    
    public void run()
    {
        this.keep_running = true;
        while(this.keep_running)
        {
            try
            {
                everySecond();
                Thread.sleep(1000);
            } catch (Exception e) {
                
            }
        }
    }
    
    public void everySecond() throws Exception
    {
        JSONObject jo = new JSONObject();
        jo.put("channelStats", this.channelStats());
        jo.put("__eventChannel", "routeputDebug");
        this.handleIncomingEvent(jo, null);
    }
    
    public void handleIncomingEvent(JSONObject j, RoutePutSession session)
    {
        String eventChannel = j.optString("__eventChannel","*");
        if (this.collectors.containsKey(eventChannel))
        {
            // This Channel has a connected collector
            RoutePutSession collector = this.collectors.get(eventChannel);
            if (session == collector)
            {
                //this connection is that collector!
                if (j.has("__targetId"))
                {
                    RoutePutSession target = findSessionById(j.optString("__targetId", ""));
                    target.send(j);
                } else {
                    broadcastJSONObject(eventChannel, j);
                }
            } else {
                // absorb all packets into collector
                collector.send(j);
            }
        } else {
            // This Channel is a complete free for all!
            if (j.has("__targetId"))
            {
                // Ok this packet has a target in the channel
                RoutePutSession target = findSessionById(j.optString("__targetId", ""));
                target.send(j);
            } else {
                // Everybody but the sender should get this packet
                broadcastJSONObject(eventChannel, j);
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
        for(RoutePutSession s : this.sessions)
        {
            if (s.getConnectionId().equals(id))
                return s;
        }
        return null;
    }
    
    private JSONObject optJSONObject(JSONObject jo, String key)
    {
        JSONObject js = new JSONObject();
        if (jo.has(key))
        {
            js = jo.getJSONObject(key);
        }
        return js;
    }
    
    public JSONObject channelStats()
    {
        JSONObject jo = new JSONObject();
        for(RoutePutSession s : this.sessions)
        {
            String dChan = s.getDefaultChannel();
            JSONObject js = optJSONObject(jo, dChan);
            
            js.put("members", js.optInt("members", 0) + 1);
            if (s.isCollector())
                js.put("collector", s.getConnectionId());
            
            jo.put(dChan, js);
        }
        return jo;
    }
    
    public JSONObject channelBreakdown()
    {
        JSONObject jo = new JSONObject();
        for(RoutePutSession s : this.sessions)
        {
            String dChan = s.getDefaultChannel();
            JSONObject js = new JSONObject();
            if (jo.has(dChan))
            {
                js = jo.getJSONObject(dChan);
            }
            js.put(s.getConnectionId(), s.toJSONObject());
            jo.put(dChan, js);
        }
        return jo;
    }
    
    public JSONObject channelBreakdown(String channel)
    {
        JSONObject jo = new JSONObject();
        for(RoutePutSession s : this.sessions)
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
        for(RoutePutSession s : this.sessions)
        {
            if (s.subscribedTo(channel))
            {
                ja.put(s.getConnectionId());
            }
        }
        return ja;
    }

    public void broadcastJSONObject(String eventChannel, JSONObject jo)
    {
        String message = jo.toString();
        for(RoutePutSession s : this.sessions)
        {
            if (s.subscribedTo(eventChannel))
            {
                try
                {
                    s.send(jo);
                } catch (Exception e) {
                    
                }
            }
        }
    }
    
    public static void logIt(String text)
    {
        JSONObject l = new JSONObject();
        l.put("__eventChannel", "routeputDebug");
        l.put("logIt",  text);
        RoutePutServer.instance.handleIncomingEvent(l, null);
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
        }
    }
}
