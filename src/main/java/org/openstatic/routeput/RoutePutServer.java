package org.openstatic.routeput;

import org.json.*;

import java.io.IOException;
import java.io.PrintStream;
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

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class RoutePutServer implements Runnable
{
    private Server httpServer;
    protected ArrayList<RoutePutSession> sessions;
    protected LinkedHashMap<String, RoutePutSession> collectors;
    protected JSONObject settings;
    protected static RoutePutServer instance;
    private Thread mainThread;
    private boolean keep_running;
    public static File blobRoot;


    public static class HeaderAddingFilter implements Filter
    {
        public HeaderAddingFilter() {}
        
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
                if (response instanceof HttpServletResponse)
                {
                    HttpServletResponse httpResponse = (HttpServletResponse) response;
                    httpResponse.addHeader("Server", "Route.put 1.0");
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
        this.sessions = new ArrayList<RoutePutSession>();
        this.collectors = new LinkedHashMap<String, RoutePutSession>();
        httpServer = new Server(settings.optInt("port", 6144));
        RoutePutServer.blobRoot = new File(settings.optString("blobRoot", "./blob/"));
        if (!RoutePutServer.blobRoot.exists())
            RoutePutServer.blobRoot.mkdir();
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.addFilter(HeaderAddingFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.setContextPath("/");
        context.addServlet(ApiServlet.class, "/api/*");
        context.addServlet(EventsWebSocketServlet.class, "/channel/*");
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
                logIt(e);
            }
        }
    }
    
    public void everySecond() throws Exception
    {
        RoutePutMessage jo = new RoutePutMessage();
        jo.put("channelStats", this.channelStats());
        jo.setChannel("routeputDebug");
        this.handleIncomingEvent(jo, null);
    }
    
    public void handleIncomingEvent(RoutePutMessage j, RoutePutSession session)
    {
        String eventChannel = j.getChannel();
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
        RoutePutMessage l = new RoutePutMessage();
        l.setChannel("routeputDebug");
        l.put("logIt",  text);
        RoutePutServer.instance.handleIncomingEvent(l, null);
    }
    
    public static void logIt(Exception e)
    {
        logIt("Exception - " + e.toString());
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
    
    public static void saveBase64Blob(String fileName, StringBuffer sb)
    {
        try
        {
            File file = new File(RoutePutServer.blobRoot, fileName);
            byte[] fileData = java.util.Base64.getDecoder().decode(sb.substring(sb.indexOf(",") + 1));
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(fileData);
            fos.close();
        } catch (Exception e) {
            logIt(e);
        }
    }
    
    public static StringBuffer loadBase64Blob(String fileName)
    {
        StringBuffer sb = new StringBuffer();
        try
        {
            File file = new File(RoutePutServer.blobRoot, fileName);
            String contentType = getContentTypeFor(fileName);
            if (file.exists())
            {
                sb.append("data:" + contentType + ";base64,");
                FileInputStream fis = new FileInputStream(file);
                byte[] bFile = new byte[(int) file.length()];
                fis.read(bFile);
                fis.close();
                sb.append(java.util.Base64.getEncoder().encodeToString(bFile));
            }
        } catch (Exception e) {
            logIt(e);
        }
        return sb;
    }
    
    /** Determine the content type of a local file */
    public static String getContentTypeFor(String filename)
    {
        String lc_file = filename.toLowerCase();
        if (lc_file.endsWith(".html") || lc_file.endsWith(".htm"))
        {
            return "text/html";
        } else if (lc_file.endsWith(".txt")) {
            return "text/plain";
        } else if (lc_file.endsWith(".css")) {
            return "text/css";
        } else if (lc_file.endsWith(".js")) {
            return "text/javascript";
        } else if (lc_file.endsWith(".jpg") || lc_file.endsWith(".jpe") || lc_file.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lc_file.endsWith(".gif")) {
            return "image/gif";
        } else if (lc_file.endsWith(".png")) {
            return "image/png";
        } else if (lc_file.endsWith(".bmp")) {
            return "image/x-ms-bmp";
        } else if (lc_file.endsWith(".mp3")) {
            return "audio/mpeg3";
        } else if (lc_file.endsWith(".zip")) {
            return "application/zip";
        } else if (lc_file.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lc_file.endsWith(".xml")) {
            return "text/xml";
        } else if (lc_file.endsWith(".mid")) {
            return "audio/midi";
        } else if (lc_file.endsWith(".tar")) {
            return "application/x-tar";
        } else if (lc_file.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lc_file.endsWith(".avi")) {
            return "video/x-msvideo";
        } else if (lc_file.endsWith(".mp4")) {
            return "video/mp4";
        } else if (lc_file.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (lc_file.endsWith(".mov")) {
            return "video/quicktime";
        } else if (lc_file.endsWith(".wmv")) {
            return "video/x-ms-wmv";
        } else if (lc_file.endsWith(".3gp")) {
            return "video/3gpp";
        } else {
            String result = MimeTypes.getDefaultMimeByExtension(filename);
            if ("".equals(result) || result == null)
                result = "application/octet-stream";
            return result;
        }
    }
}
