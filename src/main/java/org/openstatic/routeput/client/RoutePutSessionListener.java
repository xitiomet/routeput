package org.openstatic.routeput.client;

import org.openstatic.routeput.RoutePutSession;

public interface RoutePutSessionListener
{
    public void onConnect(RoutePutSession session, boolean local);
    public void onClose(RoutePutSession session, boolean local);
}