package org.openstatic.routeput;

import java.net.URL;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class InterfaceServlet extends HttpServlet
{

    public InterfaceServlet()
    {

    }
   
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
    {
        String target = request.getPathInfo();
        if (target == null)
            target = "/";
        if (target.endsWith("routeput.js"))
            target = "/routeput.js";
        if ("/".equals(target))
            target = "/index.html";
        if (target.startsWith("/watch"))
            target = "/watch.html";
        //System.err.println("Interface Path: " + target);
        URL data = getClass().getResource(target);
        if (data != null)
        {
            String contentType = BLOBManager.getContentTypeFor(target);            
            httpServletResponse.setContentType(contentType);
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
            httpServletResponse.setCharacterEncoding("iso-8859-1");
            InputStream inputStream = getClass().getResourceAsStream(target);
            OutputStream output = httpServletResponse.getOutputStream();
            inputStream.transferTo(output);
            output.flush();
        } else {
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);

        }
        //request.setHandled(true);
    }
}
