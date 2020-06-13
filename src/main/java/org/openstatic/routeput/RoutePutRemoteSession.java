package org.openstatic.routeput;

import java.util.Vector;
import java.util.Collection;
import java.util.Enumeration;
import org.json.JSONObject;

public class RoutePutRemoteSession implements RoutePutSession
{
    private String connectionId;
    private Vector<String> channels = new Vector<String>();
    private Vector<RoutePutMessageListener> listeners;
    private boolean connected;
    private RoutePutSession parent;
    private JSONObject upgradeHeaders;
    private String remoteIP;
    private String defaultChannel;

    public RoutePutRemoteSession(RoutePutSession parent, String connectionId)
    {
        this.parent = parent;
        this.connectionId = connectionId;
        this.listeners = new Vector<RoutePutMessageListener>();
        this.connected = true;
        this.defaultChannel = parent.getDefaultChannel();
    }

    public void handleMessage(RoutePutMessage m)
    {
        if (this.connectionId.equals(m.getSourceId()))
        {
            if (m.has("__sourceConnectStatus"))
            {
                this.connected = m.optBoolean("__sourceConnectStatus", false);
                this.upgradeHeaders = m.optJSONObject("upgradeHeaders");
                this.remoteIP = m.optString("remoteIP", null);
                this.defaultChannel = m.optString("__eventChannel", this.parent.getDefaultChannel());
            }
            RoutePutRemoteSession.this.listeners.forEach((r) -> {
                r.onMessage(m);
            });
        }
    }

    @Override
    public boolean isConnected()
    {
        return this.parent.isConnected() && this.connected;
    }

    public void addMessageListener(RoutePutMessageListener r)
    {
        if (!this.listeners.contains(r))
        {
            this.listeners.add(r);
        }
    }
    
    public void removeMessageListener(RoutePutMessageListener r)
    {
        if (this.listeners.contains(r))
        {
            this.listeners.remove(r);
        }
    }

    public RoutePutSession getParent()
    {
        return this.parent;
    }

    public Collection<RoutePutMessageListener> getMessageListeners()
    {
        return this.listeners;
    }

    public boolean hasMessageListener(RoutePutMessageListener r)
    {
        return this.listeners.contains(r);
    }

    public void send(JSONObject jo)
    {
        if (!jo.has("__eventChannel"))
        {
            jo.put("__eventChannel", this.getDefaultChannel());
        }
        if (!jo.has("__targetId"))
        {
            jo.put("__targetId", this.connectionId);
        }
        this.getParent().send(jo);
    }

    public String getConnectionId()
    {
        return this.connectionId;
    }

    @Override
    public String getDefaultChannel() 
    {
        return this.defaultChannel;
    }

    @Override
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.connectionId);
        jo.put("defaultChannel", this.getDefaultChannel());
        jo.put("upgradeHeaders", this.upgradeHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("_class", "RoutePutRemoteSession");
        jo.put("_listeners", this.listeners.size());
        return jo;
    }

    public boolean subscribedTo(String channel)
    {
        return (this.channels.contains(channel) ||      // Are we subscribed to the channel?
                this.getDefaultChannel().equals(channel) ||  // Is our default channel the channel?
                this.getDefaultChannel().equals("*"));       // Is our default channel * ?
    }

    @Override
    public boolean isCollector()
    {
        return false;
    }

    @Override
    public boolean isRootConnection()
    {
        return false;
    }

    @Override
    public boolean containsConnectionId(String connectionId)
    {
        return this.connectionId.equals(connectionId);
    }
}