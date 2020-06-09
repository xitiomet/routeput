package org.openstatic.routeput.client;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Vector;

import org.openstatic.routeput.RoutePutMessage;

public class RoutePutCollector implements RoutePutMessageListener
{
    RoutePutClient client;
    protected LinkedHashMap<String, RoutePutRemoteSession> sessions;
    private Vector<RoutePutRemoteSessionListener> listeners;

    public RoutePutCollector(RoutePutClient client)
    {
        this.listeners = new Vector<RoutePutRemoteSessionListener>();
        this.sessions = new LinkedHashMap<String, RoutePutRemoteSession>();
        this.client = client;
        this.client.addMessageListener(this);
        if (client.isConnected())
            requestCollector();
    }

    private void requestCollector()
    {
        RoutePutMessage rpm = new RoutePutMessage();
        rpm.setRequest("becomeCollector");
        this.client.send(rpm);
    }

    public RoutePutClient getClient()
    {
        return this.client;
    }

    public void addSessionListener(RoutePutRemoteSessionListener r)
    {
        if (!this.listeners.contains(r))
        {
            this.listeners.add(r);
        }
    }
    
    public void removeSessionListener(RoutePutRemoteSessionListener r)
    {
        if (this.listeners.contains(r))
        {
            this.listeners.remove(r);
        }
    }

    @Override
    public void onMessage(RoutePutMessage message) 
    {
        //Responses should get intercepted by the collector.
        if (message.isResponse())
        {
            if (message.getResponse().equals("connectionId"))
            {
                requestCollector();
            }
        // All other messages get sorted to the RoutePutRemoteSession
        } else {
            String sourceId = message.getSourceId();
            RoutePutRemoteSession remoteSession = null;
            if (sessions.containsKey(sourceId))
            {
                remoteSession = sessions.get(sourceId);
            } else {
                remoteSession = new RoutePutRemoteSession(this, sourceId);
                sessions.put(sourceId, remoteSession);
            }
            if (message.has("__sourceConnectStatus"))
            {
                boolean sourceConnected = message.optBoolean("__sourceConnectStatus", false);
                if (sourceConnected)
                {
                    fireSessionConnected(remoteSession);
                } else {
                    fireSessionClosed(remoteSession);
                }
            }
            remoteSession.handleMessage(message);
        }
    }

    private void fireSessionConnected(RoutePutRemoteSession remoteSession)
    {
        for (Enumeration<RoutePutRemoteSessionListener> re = ((Vector<RoutePutRemoteSessionListener>) RoutePutCollector.this.listeners.clone()).elements(); re.hasMoreElements();)
        {
            try
            {
                RoutePutRemoteSessionListener r = re.nextElement();
                r.onConnect(remoteSession);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }
    private void fireSessionClosed(RoutePutRemoteSession remoteSession)
    {
        for (Enumeration<RoutePutRemoteSessionListener> re = ((Vector<RoutePutRemoteSessionListener>) RoutePutCollector.this.listeners.clone()).elements(); re.hasMoreElements();)
        {
            try
            {
                RoutePutRemoteSessionListener r = re.nextElement();
                r.onClose(remoteSession);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }
}