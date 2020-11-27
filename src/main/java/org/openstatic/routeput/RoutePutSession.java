package org.openstatic.routeput;

import org.json.JSONObject;
import java.beans.PropertyChangeListener;

public interface RoutePutSession
{
    /* This is the only way a "session" should receive messages from its channel
       all other classes should pass messages to the session via send() */
    public void send(RoutePutMessage jo);

    /* get the unique identifier for this session, should be long alpha key to ensure uniqueness */
    public String getConnectionId();

    /* What is the "default" channel for this session */
    public RoutePutChannel getDefaultChannel();

    /* What IP address v4 or v6 did the session originate from, can be null */
    public String getRemoteIP();
    
    /* return a read only copy of the sessions properties */
    public JSONObject getProperties();

    /* JSONObject version of this session for web api */
    public JSONObject toJSONObject();

    /* Is the session connected and ready to receive messages */
    public boolean isConnected();
    
    /* is this session a root connection possibly containing other sessions */
    public boolean isRootConnection();

    /* does this connection either match the connectionId, or is the connectionId a subsession */
    public boolean containsConnectionId(String connectionId);

    /* add a message listener, whenever the session produces a message this listener should be fired */
    public void addMessageListener(RoutePutMessageListener r);
    public void removeMessageListener(RoutePutMessageListener r);

    /* Property change listeners for the session's properties */
    public void addPropertyChangeListener(PropertyChangeListener listener);
    public void removePropertyChangeListener(PropertyChangeListener listener);

    /* ONLY TRIGGERED BY Processing a RoutePutPropertyChangeMessage */
    public void firePropertyChange(String key, Object oldValue, Object newValue);
}