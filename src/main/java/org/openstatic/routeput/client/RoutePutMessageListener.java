package org.openstatic.routeput.client;

import org.openstatic.routeput.RoutePutMessage;

public interface RoutePutMessageListener 
{
    public void onMessage(RoutePutMessage message);
}