package org.openstatic.routeput;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;

public class RoutePutChannel
{
    private static HashMap<String, RoutePutChannel> channels;
    private static Thread channelTracker = null;
    private static File channelRoot;
    private static String hostname;

    private PropertyChangeSupport propertyChangeSupport;
    private String name;
    private JSONObject properties;
    protected LinkedHashMap<String, RoutePutSession> members;
    private long lastAccess;
    private RoutePutSession collector;
    private int messagesTx;
    private int messagesRx;
    private ArrayList<RoutePutChannelListener> listeners;
    private ArrayList<RoutePutMessageListener> messageListeners;

    private int msgTxPerSecond;
    private int msgRxPerSecond;

    private RoutePutChannel(String name)
    {
        RoutePutServer.logIt("Channel created " + name);
        this.propertyChangeSupport = new PropertyChangeSupport(this);
        this.msgTxPerSecond = 0;
        this.msgRxPerSecond = 0;
        this.messagesTx = 0;
        this.messagesRx = 0;
        this.name = name;
        this.lastAccess = System.currentTimeMillis();
        this.members = new LinkedHashMap<String, RoutePutSession>();
        this.listeners = new ArrayList<RoutePutChannelListener>();
        this.messageListeners = new ArrayList<RoutePutMessageListener>();
        this.collector = null;
        this.properties = new JSONObject();
        File propertiesFile = this.getPropertiesFile();
        if (propertiesFile != null)
        {
            if (propertiesFile.exists())
            {
                this.properties = RoutePutServer.loadJSONObject(propertiesFile);
            }
        }
    }

    public void setPermanent(boolean v)
    {
        this.properties.put("permanent", true);
        saveChannelProperties();
    }

    public boolean isPermanent()
    {
        return this.properties.optBoolean("permanent", false);
    }

    private void saveChannelProperties()
    {
        RoutePutServer.saveJSONObject(this.getPropertiesFile(), this.properties);
    }

    public File getBlobFolder()
    {
        File channelFolder = this.getChannelFolder();
        if (channelFolder != null)
        {
            File blobFolder = new File(channelFolder, "blob");
            if (!blobFolder.exists())
            {
                blobFolder.mkdir();
            }
            return blobFolder;
        }
        return null;
    }

    private File getPropertiesFile()
    {
        File channelFolder = this.getChannelFolder();
        if (channelFolder != null)
        {
            File channelPropertiesFile = new File(channelFolder, "properties.json");
            return channelPropertiesFile;
        } else {
            return null;
        }
    }

    private File getChannelFolder()
    {
        if (RoutePutChannel.channelRoot != null)
        {
            if (!RoutePutChannel.channelRoot.exists())
            {
                RoutePutChannel.channelRoot.mkdir();
            }
            File channelFolder = new File(RoutePutChannel.channelRoot, this.name);
            if (!channelFolder.exists())
            {
                channelFolder.mkdir();
                File blobFolder = new File(channelFolder, "blob");
                blobFolder.mkdir();
            }
            return channelFolder;
        } else {
            return null;
        }
    }

    public static void setChannelRoot(File channelRoot)
    {
        RoutePutChannel.channelRoot = channelRoot;
    }

    public static Thread initTracker()
    {
        try 
        {
            InetAddress ip;
            ip = InetAddress.getLocalHost();
            RoutePutChannel.hostname = ip.getHostName();
        } catch (Exception e) {}

        if (RoutePutChannel.channelTracker == null)
        {
            RoutePutChannel.channels = new HashMap<String, RoutePutChannel>();
            RoutePutChannel.channelTracker = new Thread(() -> {
                while(RoutePutChannel.channelTracker != null)
                {
                    try
                    {
                        Thread.sleep(1000);
                        RoutePutChannel.everySecond();
                    } catch (Exception e) {}
                }
            });
            RoutePutChannel.channelTracker.setDaemon(true);
            RoutePutChannel.channelTracker.start();
            Runtime.getRuntime().addShutdownHook(new Thread() 
            { 
                public void run() 
                {
                    // Destroy the tracker on shutdown
                    RoutePutChannel.channelTracker = null;
                } 
            });
        }
        return RoutePutChannel.channelTracker;
    }

    private static void everySecond() throws Exception
    {
        RoutePutChannel.channels.values().parallelStream().forEach((c) ->
        {
            c.msgTxPerSecond = c.messagesTx;
            c.messagesTx = 0;
            c.msgRxPerSecond = c.messagesRx;
            c.messagesRx = 0;
        });
        long idleTimeout = 600l * 1000l;
        RoutePutChannel.channels.values().removeIf((c) -> {
            boolean removeIt = (c.getIdle() > idleTimeout) && c.memberCount() == 0 && !c.isPermanent();
            if (removeIt)
            {
                c.saveChannelProperties();
                RoutePutServer.logIt("Channel \"" + c.getName() + "\" destroyed because of idle");
            } 
            return removeIt; 
        });
    }

    public static String getHostname()
    {
        return RoutePutChannel.hostname;
    }

    public static void setHostname(String hostname)
    {
        RoutePutChannel.hostname = hostname;
    }

    public int getMessagesTxPerSecond()
    {
        return this.msgTxPerSecond;
    }

    public int getMessagesRxPerSecond()
    {
        return this.msgRxPerSecond;
    }

    public String getName()
    {
        return this.name;
    }

    private void bumpTx()
    {
        this.messagesTx++;
        this.touch();
    }
    
    private void bumpRx()
    {
        this.messagesRx++;
        this.touch();
    }

    public void touch()
    {
        this.lastAccess = System.currentTimeMillis();
    }

    public boolean hasMember(RoutePutSession session)
    {
        return this.members.containsValue(session);
    }

    public synchronized void addMember(RoutePutSession session)
    {
        String connectionId = session.getConnectionId();
        if (!this.members.containsKey(connectionId))
        {
            this.members.put(connectionId, session);
            RoutePutMessage jo = new RoutePutMessage();
            jo.setSourceId(connectionId);
            jo.setChannel(this);
            jo.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
            jo.setMetaField("connected", true);
            jo.setMetaField("remoteIP", session.getRemoteIP());
            jo.setMetaField("properties", session.getProperties());
            this.broadcast(jo);
            if (session.isRootConnection())
                this.transmitMembers(session);
            listeners.parallelStream().forEach((l) -> {
                l.onJoin(this, session);
            });
            this.touch();
        }
    }

    public synchronized void removeMember(RoutePutSession session)
    {
        if (this.members.containsValue(session))
        {
            String connectionId = session.getConnectionId();
            this.members.remove(session.getConnectionId());
            RoutePutMessage jo = new RoutePutMessage();
            jo.setSourceId(connectionId);
            jo.setChannel(this);
            jo.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
            jo.setMetaField("connected", false);
            jo.setMetaField("remoteIP", session.getRemoteIP());
            jo.setMetaField("properties", session.getProperties());
            this.broadcast(jo);
            listeners.parallelStream().forEach((l) -> {
                l.onLeave(this, session);
            });
            this.touch();
            // I know this is sloppy but i need to know when a remote session leaves its last channel
            if (session instanceof RoutePutRemoteSession)
            {
                RoutePutRemoteSession rprs = (RoutePutRemoteSession) session;
                rprs.maybeDestroy();
            }
        }
        if (session == this.collector)
            this.collector = null;
    }

    public void addChannelListener(RoutePutChannelListener rpcl)
    {
        if (!this.listeners.contains(rpcl))
        {
            this.listeners.add(rpcl);
        }
    }
    
    public void removeChannelListener(RoutePutChannelListener rpcl)
    {
        if (this.listeners.contains(rpcl))
        {
            this.listeners.remove(rpcl);
        }
    }

    public void addMessageListener(RoutePutMessageListener rpml)
    {
        if (!this.messageListeners.contains(rpml))
        {
            this.messageListeners.add(rpml);
        }
    }
    
    public void removeMessageListener(RoutePutMessageListener rpml)
    {
        if (this.messageListeners.contains(rpml))
        {
            this.messageListeners.remove(rpml);
        }
    }

    public void setCollector(RoutePutSession collector)
    {
        this.collector = collector;
    }

    public RoutePutSession getCollector()
    {
        return this.collector;
    }

    public boolean hasCollector()
    {
        return this.collector != null;
    }

    public int memberCount()
    {
        return this.members.size();
    }

    public RoutePutSession findMemberById(String id)
    {
        if (this.members.containsKey(id))
        {
            return this.members.get(id);
        }
        return null;
    }

    public void transmitMembers(RoutePutSession session)
    {
        this.members.values().parallelStream().forEach((m) -> {
            if (m != session)
            {
                RoutePutMessage jo = new RoutePutMessage();
                jo.setSourceId(m.getConnectionId());
                jo.setChannel(this);
                jo.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
                jo.setMetaField("connected", true);
                jo.setMetaField("properties", m.getProperties());
                session.send(jo);
            }
        });
    }

    public void handleMessage(RoutePutSession session, RoutePutMessage j)
    {
        RoutePutChannel mChan = j.getRoutePutChannel();
        if (this.equals(mChan))
        {
            bumpRx();
            if (RoutePutChannel.hostname != null)
            {
                j.appendMetaArray("hops", RoutePutChannel.hostname);
            }
            if (j.hasMetaField("rssi"))
            {
                int rssi = j.getRoutePutMeta().optInt("rssi",-120);
                this.properties.put("rssi", rssi);
                if (session !=null)
                {
                    session.getProperties().put("rssi", rssi);
                }
            }
            if (j.hasMetaField("setChannelProperty"))
            {
                JSONObject storeRequest = j.getRoutePutMeta().optJSONObject("setChannelProperty");
                for(String k : storeRequest.keySet())
                {
                    String v = storeRequest.getString(k);
                    Object oldValue = this.properties.opt(k);
                    Object newValue = j.getPathValue(v);
                    this.properties.put(k, newValue);
                    this.propertyChangeSupport.firePropertyChange(k, oldValue, newValue);
                }
                saveChannelProperties();
            }
            if (j.isType(RoutePutMessage.TYPE_CONNECTION_STATUS))
            {
                if (session != null)
                {
                    // We were given information about the session lets handle accordingly
                    boolean c = j.getRoutePutMeta().optBoolean("connected", false);
                    if (c)
                    {
                        this.addMember(session);
                    } else {
                        this.removeMember(session);
                    }
                } else {
                    // We dont know what session this belongs to, so just broadcast it
                    this.broadcast(j);
                }
            } else if (this.hasCollector()) {
                // This Channel has a connected collector
                RoutePutSession collector = this.getCollector();
                if (session == collector && session != null)
                {
                    //this connection is that collector!
                    if (j.hasTargetId())
                    {
                        RoutePutSession target = this.findMemberById(j.getTargetId());
                        if (target != null)
                        {
                            j.setMetaField("collectorTargeted", true);
                            bumpTx();
                            target.send(j);
                        }
                    } else {
                        j.setMetaField("collectorBroadcast", true);
                        this.broadcast(j);
                    }
                } else {
                    // absorb all packets into collector
                    j.setMetaField("collectorAbsorbed", true);
                    bumpTx();
                    collector.send(j);
                }
            } else {
                // This Channel is a complete free for all!
                if (j.hasTargetId())
                {
                    // Ok this packet has a target in the channel
                    RoutePutSession target = this.findMemberById(j.getTargetId());
                    if (target != null)
                    {
                        bumpTx();
                        target.send(j);
                    } else {
                        RoutePutServer.logWarning("PACKET LOST (Target wasn't found): " + j.toString());
                    }
                } else {
                    // Everybody but the sender should get this packet
                    this.broadcast(j);
                }
            }
            if (!"routeputDebug".equals(mChan.getName()))
            {
                if (j.isType(RoutePutMessage.TYPE_LOG_ERROR) || j.isType(RoutePutMessage.TYPE_LOG_INFO) || j.isType(RoutePutMessage.TYPE_LOG_WARNING))
                {
                    RoutePutMessage l = new RoutePutMessage();
                    //l.setSourceId("debug-" + RoutePutChannel.getHostname());
                    l.setType(j.getType());
                    l.setChannel("routeputDebug");
                    l.put("text",  j.getChannel() + "(" + j.getSourceId() + ") " + j.getType().toUpperCase() + " - " + j.optString("text", "No details provided"));
                    RoutePutChannel.getChannel("routeputDebug").broadcast(l);
                }
            }
            messageListeners.parallelStream().forEach((l) -> {
                l.onMessage(j);
            });
        } else {
            RoutePutServer.logWarning(this.getName() + " was asked to handle a packet that didnt belong to it! Intended for " + mChan.getName());
        }
    }

    private void broadcast(RoutePutMessage jo)
    {
        this.members.values().parallelStream().forEach((s) ->
        {
            if (s.isRootConnection())
            {
                try
                {
                    // Never Send the event to the creator or its relay
                    if (!s.containsConnectionId(jo.getSourceId()))
                    {
                        bumpTx();
                        s.send(jo);
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        });
    }

    public Collection<RoutePutSession> getMembers()
    {
        return this.members.values();
    }

    public JSONObject membersAsJSONObject()
    {
        JSONObject jo = new JSONObject();
        for(RoutePutSession s: this.getMembers())
        {
            jo.put(s.getConnectionId(), s.toJSONObject());
        }
        return jo;
    }

    public JSONArray membersAsJSONArray()
    {
        JSONArray ja = new JSONArray();
        for(RoutePutSession s: this.getMembers())
        {
            ja.put(s.toJSONObject());
        }
        return ja;
    }

    public void setProperty(RoutePutSession session, String key, Object value)
    {
        Object oldValue = this.properties.opt(key);
        this.properties.put(key, value);
        
        RoutePutMessage setChannelPropertyMessage = new RoutePutMessage();
        setChannelPropertyMessage.setSource(session);
        setChannelPropertyMessage.setChannel(this);
        setChannelPropertyMessage.put(key,value);

        JSONObject setDirective = new JSONObject();
        setDirective.put(key, key);
        
        setChannelPropertyMessage.getRoutePutMeta().put("setChannelProperty", setDirective);
        this.broadcast(setChannelPropertyMessage);

        this.saveChannelProperties();
        this.propertyChangeSupport.firePropertyChange(key, oldValue, value);
    }

    public void removeProperty(RoutePutSession session, String key)
    {
        Object oldValue = this.properties.opt(key);
        this.properties.remove(key);
        this.saveChannelProperties();
        this.propertyChangeSupport.firePropertyChange(key, oldValue, null);
    }

    public JSONObject getProperties()
    {
        return this.properties;
    }

    public static Collection<RoutePutChannel> getChannels()
    {
        return RoutePutChannel.channels.values();
    }

    public static synchronized RoutePutChannel getChannel(String name)
    {
        initTracker();
        RoutePutChannel chan = RoutePutChannel.channels.get(name);
        if (chan == null)
        {
            chan = new RoutePutChannel(name);
            RoutePutChannel.channels.put(name, chan);
        } else {
            chan.touch();
        }
        return chan;
    }

    public static synchronized void removeFromAllChannels(RoutePutSession session)
    {
        RoutePutChannel.channels.values().stream().forEach((c) -> {
            c.removeMember(session);
        });
        if (session.isRootConnection() && RoutePutRemoteSession.isInitialized())
        {
            for(RoutePutRemoteSession rSession : RoutePutRemoteSession.children(session))
            {
                RoutePutChannel.removeFromAllChannels(rSession);
            }
        }
    }

    public static synchronized Collection<RoutePutChannel> channelsWithMember(RoutePutSession session)
    {
        return RoutePutChannel.channels.values()
                .stream().filter((c) -> c.hasMember(session))
                .collect(Collectors.toList());
    }

    public static JSONObject channelBreakdown()
    {
        JSONObject jo = new JSONObject();
        for(RoutePutChannel c : channels.values())
        {
            String dChan = c.getName();
            jo.put(dChan, c.toJSONObject());
        }
        return jo;
    }

    public boolean equals(RoutePutChannel chan)
    {
        return this.name.equals(chan.getName());
    }

    public boolean equals(String chan)
    {
        return this.name.equals(chan);
    }

    public String toString()
    {
        return this.name;
    }

    public long getIdle()
    {
        return System.currentTimeMillis() - this.lastAccess;
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) 
    {
        this.propertyChangeSupport.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener)
    {
        this.propertyChangeSupport.removePropertyChangeListener(listener);
    }

    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("name", this.name);
        jo.put("lastAccess", this.lastAccess);
        jo.put("idle", this.getIdle());
        jo.put("members", this.membersAsJSONObject());
        jo.put("memberCount", this.memberCount());
        jo.put("properties", this.getProperties());
        jo.put("msgTxPerSecond", this.msgTxPerSecond);
        jo.put("msgRxPerSecond", this.msgRxPerSecond);
        if (this.collector != null)
        {
            jo.put("collector", this.collector.getConnectionId());
        }
        return jo;
    }
}