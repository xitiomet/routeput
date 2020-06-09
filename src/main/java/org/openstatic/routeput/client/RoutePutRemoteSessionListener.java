package org.openstatic.routeput.client;

public interface RoutePutRemoteSessionListener
{
    public void onConnect(RoutePutRemoteSession session);
    public void onClose(RoutePutRemoteSession session);
}