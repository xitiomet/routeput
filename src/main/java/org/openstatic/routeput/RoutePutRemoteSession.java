package org.openstatic.routeput;

import java.util.Vector;
import java.util.Collection;
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
    private RoutePutChannel defaultChannel;
    private JSONObject properties;

    public RoutePutRemoteSession(RoutePutSession parent, String connectionId)
    {
        this.parent = parent;
        this.connectionId = connectionId;
        this.listeners = new Vector<RoutePutMessageListener>();
        this.connected = true;
        this.defaultChannel = parent.getDefaultChannel();
        this.properties = new JSONObject();
    }

    public void handleMessage(RoutePutMessage m)
    {
        if (this.connectionId.equals(m.getSourceId()))
        {
            if (m.hasMetaField("setSessionProperty"))
            {
                JSONObject storeRequest = m.getRoutePutMeta().optJSONObject("setSessionProperty");
                for(String k : storeRequest.keySet())
                {
                    String v = storeRequest.getString(k);
                    this.properties.put(k, m.getPathValue(v));
                }
            }
            if (m.hasMetaField("setChannelProperty"))
            {
                JSONObject storeRequest = m.getRoutePutMeta().optJSONObject("setChannelProperty");
                for(String k : storeRequest.keySet())
                {
                    String v = storeRequest.getString(k);
                    this.defaultChannel.setProperty(k, m.getPathValue(v));
                }
            }
            if (m.isType(RoutePutMessage.TYPE_CONNECTION_STATUS))
            {
                this.connected = m.optBoolean("connected", false);
                this.upgradeHeaders = m.optJSONObject("upgradeHeaders");
                this.properties = m.optJSONObject("properties");
                this.remoteIP = m.optString("remoteIP", null);
                this.defaultChannel = m.getRoutePutChannel();
                if (this.connected)
                {
                    this.defaultChannel.addMember(this);
                } else {
                    this.defaultChannel.removeMember(this);
                }
            } else {
                RoutePutRemoteSession.this.listeners.parallelStream().forEach((r) -> {
                    r.onMessage(m);
                });
            }
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

    public void send(RoutePutMessage jo)
    {
        jo.setChannelIfNull(this.getDefaultChannel());
        jo.setTargetId(this.connectionId);
        this.getParent().send(jo);
    }

    public String getConnectionId()
    {
        return this.connectionId;
    }

    @Override
    public RoutePutChannel getDefaultChannel() 
    {
        return this.defaultChannel;
    }

    @Override
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.connectionId);
        jo.put("defaultChannel", this.getDefaultChannel().getName());
        //jo.put("upgradeHeaders", this.upgradeHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("properties", this.properties);
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

    @Override
    public String getProperty(String key, String defaultValue)
    {
        if (this.properties != null)
        {
            return this.properties.optString(key, defaultValue);
        } else {
            return defaultValue;
        }
    }

    @Override
    public JSONObject getProperties()
    {
        return this.properties;
    }
}