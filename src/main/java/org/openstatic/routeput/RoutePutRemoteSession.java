package org.openstatic.routeput;

import java.util.Vector;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.HashMap;

import org.json.JSONObject;

public class RoutePutRemoteSession implements RoutePutSession
{
    private static HashMap<String, RoutePutRemoteSession> sessions;

    private String connectionId;
    private Vector<RoutePutMessageListener> listeners;
    private RoutePutSession parent;
    private String remoteIP;
    private RoutePutChannel defaultChannel;
    private JSONObject properties;

    public static void init()
    {
        if (RoutePutRemoteSession.sessions == null)
        {
            RoutePutRemoteSession.sessions = new HashMap<String, RoutePutRemoteSession>();
        }
    }

    public static boolean isInitialized()
    {
        return RoutePutRemoteSession.sessions != null;
    }

    public static synchronized void handleRoutedMessage(RoutePutSession parent, RoutePutMessage jo)
    {
        init();
        String sourceId = jo.getSourceId();
        if (jo.isType(RoutePutMessage.TYPE_CONNECTION_STATUS))
        {
            boolean c = jo.getRoutePutMeta().optBoolean("connected", false);
            if (c)
            {
                // If this is a message notifying us that a downstream client connected
                // lets find that connaction or create it and pass the message off.
                RoutePutRemoteSession remoteSession = null;
                if (RoutePutRemoteSession.sessions.containsKey(sourceId))
                {
                    remoteSession = RoutePutRemoteSession.sessions.get(sourceId);
                } else {
                    final RoutePutRemoteSession finalRemoteSession = new RoutePutRemoteSession(parent, sourceId);
                    remoteSession = finalRemoteSession;
                    RoutePutRemoteSession.sessions.put(sourceId, remoteSession);
                }
                remoteSession.handleMessage(jo);
            } else {
                // Seems like this is a disconnect message, lets just remove the connection
                // and pass that status along.
                if (RoutePutRemoteSession.sessions.containsKey(sourceId))
                {
                    RoutePutRemoteSession remoteSession = RoutePutRemoteSession.sessions.get(sourceId);
                    remoteSession.handleMessage(jo);
                    RoutePutRemoteSession.sessions.remove(sourceId);
                }
            }
        } else {
            // looks like this is a normal message for a stored remote connection
            RoutePutRemoteSession remoteSession = null;
            if (RoutePutRemoteSession.sessions.containsKey(sourceId))
            {
                remoteSession = RoutePutRemoteSession.sessions.get(sourceId);
                remoteSession.handleMessage(jo);
            } else {
                // FOR NOW ONLY... LETS LET PACKETS WITH UNIDENTIFIED SOURCES PASS ALONG...
                RoutePutChannel c = jo.getRoutePutChannel();
                c.handleMessage(null, jo);
            }
        }
    }

    public RoutePutRemoteSession(RoutePutSession parent, String connectionId)
    {
        this.parent = parent;
        this.connectionId = connectionId;
        this.listeners = new Vector<RoutePutMessageListener>();
        this.defaultChannel = parent.getDefaultChannel();
        this.properties = new JSONObject();
    }

    public void handleMessage(RoutePutMessage m)
    {
        if (this.connectionId.equals(m.getSourceId()))
        {
            RoutePutChannel msgChannel = m.getRoutePutChannel();
            if (m.isType(RoutePutMessage.TYPE_CONNECTION_STATUS))
            {
                boolean connected = m.getRoutePutMeta().optBoolean("connected", false);
                this.properties = m.getRoutePutMeta().optJSONObject("properties");
                this.remoteIP = m.getRoutePutMeta().optString("remoteIP", null);
                if (this.defaultChannel == null)
                {
                    this.defaultChannel = msgChannel;
                }
                if (connected)
                {
                    msgChannel.addMember(this);
                } else {
                    msgChannel.removeMember(this);
                }
            }
            if (m.hasMetaField("setSessionProperty"))
            {
                JSONObject storeRequest = m.getRoutePutMeta().optJSONObject("setSessionProperty");
                for(String k : storeRequest.keySet())
                {
                    String v = storeRequest.getString(k);
                    this.getProperties().put(k, m.getPathValue(v));
                }
            }
            msgChannel.handleMessage(this, m);
            RoutePutRemoteSession.this.listeners.parallelStream().forEach((r) -> {
                r.onMessage(m);
            });
        } else {
            RoutePutServer.logIt("PACKET LOST (RoutePutRemoteSession asked to handle stray packet): " + m.toString());
        }
    }

    @Override
    public boolean isConnected()
    {
        return this.parent.isConnected() && (RoutePutChannel.channelsWithMember(this).size() > 0);
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

    public boolean hasParent(RoutePutSession session)
    {
        return this.parent == session;
    }

    public static Collection<RoutePutRemoteSession> children(RoutePutSession parent)
    {
        return RoutePutRemoteSession.sessions.values().stream().filter((c) -> (c.hasParent(parent))).collect(Collectors.toList());
    }

    public static boolean isChild(RoutePutSession parent, String childConnectionId)
    {
        if (RoutePutRemoteSession.sessions.containsKey(childConnectionId))
        {
            return RoutePutRemoteSession.sessions.get(childConnectionId).hasParent(parent);
        } else {
            return false;
        }
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

    public String getRemoteIP()
    {
        return this.remoteIP;
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
        jo.put("_parentConnected", this.parent.isConnected());
        jo.put("_parentConnectionId", this.parent.getConnectionId());
        jo.put("remoteIP", this.remoteIP);
        jo.put("properties", this.properties);
        jo.put("_class", "RoutePutRemoteSession");
        jo.put("_listeners", this.listeners.size());
        return jo;
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