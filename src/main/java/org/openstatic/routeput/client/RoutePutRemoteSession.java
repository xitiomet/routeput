package org.openstatic.routeput.client;

import java.util.Vector;
import java.util.Collection;
import java.util.Enumeration;
import org.openstatic.routeput.RoutePutMessage;
import org.json.JSONObject;

public class RoutePutRemoteSession 
{
    private String connectionId;
    private Vector<RoutePutMessageListener> listeners;
    private boolean connected;
    private RoutePutCollector collector;

    protected void handleMessage(RoutePutMessage m)
    {
        if (this.connectionId.equals(m.getSourceId()))
        {
            if (m.has("__sourceConnectStatus"))
            {
                this.connected = m.optBoolean("__sourceConnectStatus", false);
            } else {
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
    }

    public boolean isConnected()
    {
        return this.connected;
    }

    protected RoutePutRemoteSession(RoutePutCollector collector, String connectionId)
    {
        this.collector = collector;
        this.connectionId = connectionId;
        this.listeners = new Vector<RoutePutMessageListener>();
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

    public RoutePutCollector getCollector()
    {
        return this.collector;
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
        jo.put("__targetId", this.connectionId);
        this.collector.getClient().send(jo);
    }

    public String getConnectionId()
    {
        return this.connectionId;
    }
}