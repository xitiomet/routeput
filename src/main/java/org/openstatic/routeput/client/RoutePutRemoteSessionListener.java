package org.openstatic.routeput.client;

import org.openstatic.routeput.RoutePutRemoteSession;

public interface RoutePutRemoteSessionListener
{
    public void onConnect(RoutePutRemoteSession session);
    public void onClose(RoutePutRemoteSession session);
}