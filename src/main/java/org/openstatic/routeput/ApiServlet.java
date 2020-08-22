package org.openstatic.routeput;

import org.json.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.StringTokenizer;
import java.io.BufferedReader;

public class ApiServlet extends HttpServlet
{
    public ApiServlet()
    {
        
    }
    
    public RoutePutMessage readRoutePutMessagePOST(HttpServletRequest request)
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
            RoutePutServer.logIt(e);
        }

        try
        {
            RoutePutMessage jsonObject =  new RoutePutMessage(jb.toString().trim());
            return jsonObject;
        } catch (JSONException e) {
            RoutePutServer.logIt(e);
            return new RoutePutMessage();
        }
    }

    public JSONArray readJSONArrayPOST(HttpServletRequest request)
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
            RoutePutServer.logIt(e);
        }

        try
        {
            JSONArray jsonArray =  new JSONArray(jb.toString().trim());
            return jsonArray;
        } catch (JSONException e) {
            RoutePutServer.logIt(e);
            return new JSONArray();
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
    protected void doPost(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        httpServletResponse.setContentType("text/javascript");
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        httpServletResponse.setCharacterEncoding("iso-8859-1");
        httpServletResponse.addHeader("Server", "Routeput 1.0");
        String target = request.getPathInfo();
        //System.err.println("Path: " + target);
        JSONObject response = new JSONObject();
        try
        {
            String sourceId = "api-" + RoutePutServer.instance.hostname;
            if (target.startsWith("/post/"))
            {
                RoutePutMessage post = readRoutePutMessagePOST(request);
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens())
                {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens())
                    {
                        post.setChannel(RoutePutChannel.getChannel(st.nextToken()));
                    }
                    if (token.equals("id") && st.hasMoreTokens())
                    {
                        sourceId = st.nextToken();
                    }
                }
                post.setSourceIdIfNull(sourceId);
                //RoutePutServer.logIt("API: " + target + "\n" + post.toString());
                post.setMetaField("apiPost", true);
                RoutePutChannel chan = post.getRoutePutChannel();
                chan.bumpRx();
                RoutePutServer.instance.handleIncomingEvent(post, null);
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
                    if (msg instanceof JSONObject)
                    {
                        RoutePutMessage rMsg = new RoutePutMessage((JSONObject) msg);
                        rMsg.setChannelIfNull(finalChannel);
                        rMsg.setSourceIdIfNull(finalSourceId);
                        //RoutePutServer.logIt("API: " + target + "\n" + rMsg.toString());
                        rMsg.setMetaField("apiBatch", true);
                        RoutePutChannel chan = rMsg.getRoutePutChannel();
                        chan.bumpRx();
                        RoutePutServer.instance.handleIncomingEvent(rMsg, null);
                    }
                });
            }
        } catch (Exception e) {
            RoutePutServer.logIt(e);
        }
        httpServletResponse.getWriter().println(response.toString());
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
    {
        httpServletResponse.setContentType("text/javascript");
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        httpServletResponse.setCharacterEncoding("iso-8859-1");
        httpServletResponse.addHeader("Server", "Routeput 1.0");
        String target = request.getPathInfo();
        //System.err.println("Path: " + target);
        //RoutePutServer.logIt("API Request: " + target);
        JSONObject response = new JSONObject();
        try
        {
            if (target.startsWith("/channel/"))
            {
                StringTokenizer st = new StringTokenizer(target, "/");
                while (st.hasMoreTokens())
                {
                    String token = st.nextToken();
                    if (token.equals("channel") && st.hasMoreTokens())
                    {
                        String channelName = st.nextToken();
                        RoutePutChannel channel = RoutePutChannel.getChannel(channelName);
                        response = channel.toJSONObject();
                        if (st.hasMoreTokens())
                        {
                            token = st.nextToken();
                            if ("removeProperty".equals(token) && st.hasMoreTokens())
                            {
                                token = st.nextToken();
                                channel.removeProperty(token);
                                response = channel.toJSONObject();
                            } else if ("properties".equals(token)) {
                                response = channel.getProperties();
                            } else if ("members".equals(token)) {
                                response = channel.membersAsJSONObject();
                            } else if ("setProperty".equals(token)) {
                                request.getParameterMap().forEach((key, value) -> {
                                    if ("true".equals(value[0]))
                                    {
                                        channel.setProperty(key, true);
                                    } else if ("false".equals(value[0])) {
                                        channel.setProperty(key, false);
                                    } else {
                                        channel.setProperty(key, value[0]);
                                    }
                                });
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
                RoutePutSession session = RoutePutServer.instance.connectUpstream(channel, uri);
                response.put("session", session.toJSONObject());
            }
        } catch (Exception x) {
            RoutePutServer.logIt(x);
        }
        httpServletResponse.getWriter().println(response.toString());
        //request.setHandled(true);
    }
}
