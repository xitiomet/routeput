package org.openstatic;

import org.json.*;

import java.io.IOException;
import java.io.BufferedReader;

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


public class RoutePutServer
{
    private Server httpServer;
    protected ArrayList<RoutePutSession> sessions;
    protected LinkedHashMap<String, RoutePutSession> collectors;

    protected static RoutePutServer instance;
    private String staticRoot;
    
    public static void main(String[] args)
    {
        RoutePutServer rps = new RoutePutServer();
        rps.setState(true);
        
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
    
    public RoutePutServer()
    {
        RoutePutServer.instance = this;
        this.sessions = new ArrayList<RoutePutSession>();
        this.collectors = new LinkedHashMap<String, RoutePutSession>();
        httpServer = new Server(6144);
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(ApiServlet.class, "/api/*");
        context.addServlet(EventsWebSocketServlet.class, "/channel/*");
        try
        {
            URL url = RoutePutServer.class.getResource("/routeput-res/index.html");
            this.staticRoot = url.toString().replaceAll("index.html","");
            DefaultServlet defaultServlet = new DefaultServlet();
            ServletHolder holderPwd = new ServletHolder("default", defaultServlet);
            holderPwd.setInitParameter("resourceBase", this.staticRoot);
            context.addServlet(holderPwd, "/*");
            
            /*
            final HttpConfiguration httpConfiguration = new HttpConfiguration();
            httpConfiguration.setSecureScheme("https");
            httpConfiguration.setSecurePort(6145);
            final SslContextFactory sslContextFactory = new SslContextFactory(this.staticRoot + "midi-tools.jks");
            sslContextFactory.setKeyStorePassword("miditools");
            final HttpConfiguration httpsConfiguration = new HttpConfiguration(httpConfiguration);
            httpsConfiguration.addCustomizer(new SecureRequestCustomizer());
            final ServerConnector httpsConnector = new ServerConnector(httpServer,
                new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(httpsConfiguration));
            httpsConnector.setPort(6145);
            httpServer.addConnector(httpsConnector);
            */
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        httpServer.setHandler(context);
    }
    
    public void handleIncomingEvent(JSONObject j, RoutePutSession session)
    {
        String eventChannel = j.optString("__eventChannel","*");
        if (this.collectors.containsKey(eventChannel))
        {
            RoutePutSession collector = this.collectors.get(eventChannel);
            if (session == collector)
            {
                if (j.has("__targetId"))
                {
                    RoutePutSession target = findSessionById(j.optString("__targetId", ""));
                    target.send(j);
                } else {
                    broadcastJSONObject(eventChannel, j);
                }
            } else {
                collector.send(j);
            }
        } else {
            broadcastJSONObject(eventChannel, j);
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

    public static class EventsWebSocketServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            //factory.getPolicy().setIdleTimeout(10000);
            factory.register(RoutePutSession.class);
        }
    }

    public static class ApiServlet extends HttpServlet
    {
        public JSONObject readJSONObjectPOST(HttpServletRequest request)
        {
            StringBuffer jb = new StringBuffer();
            String line = null;
            try
            {
                BufferedReader reader = request.getReader();
                while ((line = reader.readLine()) != null)
                {
                    jb.append(line);
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }

            try
            {
                JSONObject jsonObject =  new JSONObject(jb.toString());
                return jsonObject;
            } catch (JSONException e) {
                e.printStackTrace(System.err);
                return new JSONObject();
            }
        }

        public boolean isNumber(String v)
        {
            try
            {
                Integer.parseInt(v);
                return true;
            } catch(NumberFormatException e){
                return false;
            }
        }
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            httpServletResponse.setContentType("text/javascript");
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
            httpServletResponse.setCharacterEncoding("iso-8859-1");
            String target = request.getPathInfo();
            System.err.println("Path: " + target);
            JSONObject response = new JSONObject();
            try
            {
                if ("/rules/add/".equals(target))
                {
                    
                } else if ("/mappings/".equals(target)) {

                }
            } catch (Exception x) {
                x.printStackTrace(System.err);
            }
            httpServletResponse.getWriter().println(response.toString());
            //request.setHandled(true);
        }
    }
}
