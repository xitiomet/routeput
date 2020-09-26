package org.openstatic.routeput;

import org.json.JSONObject;
import java.beans.PropertyChangeListener;

public interface RoutePutSession
{
    public void send(RoutePutMessage jo);

    public String getConnectionId();
    public RoutePutChannel getDefaultChannel();
    public String getRemoteIP();
    
    public JSONObject getProperties();
    public JSONObject toJSONObject();

    public boolean isConnected();
    public boolean isCollector();
    public boolean isRootConnection();
    public boolean containsConnectionId(String connectionId);

    public void addMessageListener(RoutePutMessageListener r);
    public void removeMessageListener(RoutePutMessageListener r);

    public void addPropertyChangeListener(PropertyChangeListener listener);
    public void removePropertyChangeListener(PropertyChangeListener listener);
}