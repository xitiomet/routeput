package org.openstatic.routeput;

import org.json.JSONObject;

public interface RoutePutSession 
{
    public void send(JSONObject jo);

    public String getConnectionId();
    public String getDefaultChannel();

    public JSONObject toJSONObject();

    public boolean isConnected();
    public boolean isCollector();
    public boolean isRootConnection();
    public boolean containsConnectionId(String connectionId);
    public boolean subscribedTo(String channel);
}