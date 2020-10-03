package org.openstatic.routeput;

public interface RoutePutMessageListener 
{
    public void onMessage(RoutePutSession session, RoutePutMessage message);
}