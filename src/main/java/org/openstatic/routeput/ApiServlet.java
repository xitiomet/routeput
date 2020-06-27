package org.openstatic.routeput;

import org.json.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.BufferedReader;

public class ApiServlet extends HttpServlet
{
    public ApiServlet()
    {
        
    }
    
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
            RoutePutServer.logIt(e);
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
        httpServletResponse.addHeader("Server", "Routeput 1.0");
        String target = request.getPathInfo();
        //System.err.println("Path: " + target);
        RoutePutServer.logIt("API Request: " + target);
        JSONObject response = new JSONObject();
        try
        {
            if ("/channels/".equals(target))
            {
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
