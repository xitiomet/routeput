package org.openstatic;

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class EventsWebSocketServlet extends WebSocketServlet
{
    public EventsWebSocketServlet()
    {
        
    }
    
    @Override
    public void configure(WebSocketServletFactory factory)
    {
        //factory.getPolicy().setIdleTimeout(10000);
        factory.register(RoutePutSession.class);
    }
}
