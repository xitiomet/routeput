package org.openstatic.routeput.client;

import java.util.Vector;
import java.util.Collection;
import java.util.Enumeration;
import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutSession;
import org.json.JSONObject;

public class RoutePutRemoteSession implements RoutePutSession
{
    private String connectionId;
    private Vector<RoutePutMessageListener> listeners;
    private boolean connected;
    private RoutePutClient client;
    private JSONObject upgradeHeaders;
    private String remoteIP;

    protected RoutePutRemoteSession(RoutePutClient client, String connectionId)
    {
        this.client = client;
        this.connectionId = connectionId;
        this.listeners = new Vector<RoutePutMessageListener>();
        this.connected = true;
    }

    protected void handleMessage(RoutePutMessage m)
    {
        if (this.connectionId.equals(m.getSourceId()))
        {
            if (m.has("__sourceConnectStatus"))
            {
                this.connected = m.optBoolean("__sourceConnectStatus", false);
                this.upgradeHeaders = m.optJSONObject("upgradeHeaders");
                this.remoteIP = m.optString("remoteIP", null);
            }
            for (Enumeration<RoutePutMessageListener> re = ((Vector<RoutePutMessageListener>) RoutePutRemoteSession.this.listeners.clone()).elements(); re.hasMoreElements();)
            {
                try
                {
                    RoutePutMessageListener r = re.nextElement();
                    r.onMessage(m);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    @Override
    public boolean isConnected()
    {
        return this.client.isConnected() && this.connected;
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

    public RoutePutClient getClient()
    {
        return this.client;
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
        this.getClient().send(jo);
    }

    public String getConnectionId()
    {
        return this.connectionId;
    }

    @Override
    public String getDefaultChannel() 
    {
        return this.client.getDefaultChannel();
    }

    @Override
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.connectionId);
        jo.put("defaultChannel", this.getDefaultChannel());
        if (this.upgradeHeaders != null)
            jo.put("upgradeHeaders", this.upgradeHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("_class", "RoutePutRemoteSession");
        return jo;
    }

    @Override
    public boolean subscribedTo(String channel) 
    {
        if (channel != null)
        {
            return channel.equals(this.getDefaultChannel());
        }
        return false;
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
        // TODO Auto-generated method stub
        return this.connectionId.equals(connectionId);
    }
}