package org.openstatic.routeput;

public interface RoutePutChannelListener
{
    public void onJoin(RoutePutChannel channel, RoutePutSession session);
    public void onLeave(RoutePutChannel channel, RoutePutSession session);
}
