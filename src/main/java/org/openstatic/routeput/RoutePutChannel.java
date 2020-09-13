package org.openstatic.routeput;

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

    private String name;
    private JSONObject properties;
    protected LinkedHashMap<String, RoutePutSession> members;
    private long lastAccess;
    private RoutePutSession collector;
    private int messagesTx;
    private int messagesRx;
    private ArrayList<RoutePutChannelListener> listeners;
    private int msgTxPerSecond;
    private int msgRxPerSecond;
    private boolean permanent;

    private RoutePutChannel(String name)
    {
        RoutePutServer.logIt("Channel created " + name);
        this.msgTxPerSecond = 0;
        this.msgRxPerSecond = 0;
        this.messagesTx = 0;
        this.messagesRx = 0;
        this.name = name;
        this.lastAccess = System.currentTimeMillis();
        this.members = new LinkedHashMap<String, RoutePutSession>();
        this.listeners = new ArrayList<RoutePutChannelListener>();
        this.collector = null;
        this.properties = new JSONObject();
        this.permanent = false;
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
        this.permanent = v;
    }

    public boolean isPermanent()
    {
        return this.permanent;
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
                this.setProperty("rssi", rssi);
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
                    this.setProperty(k, j.getPathValue(v));
                }
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
                        RoutePutServer.logIt("PACKET LOST (Target wasn't found): " + j.toString());
                    }
                } else {
                    // Everybody but the sender should get this packet
                    this.broadcast(j);
                }
            }
            if (j.isType(RoutePutMessage.TYPE_ERROR))
            {
                String details = j.getRoutePutMeta().optString("message","");
                RoutePutServer.logIt("<b style=\"color: #8b0000;\">" + this.getName() + " ERROR: " + details + "</b>");
                listeners.parallelStream().forEach((l) -> {
                    l.onError(this, details, j);
                });
            }
        } else {
            RoutePutServer.logIt(this.getName() + " was asked to handle a packet that didnt belong to it! Intended for " + mChan.getName());
        }
    }

    public void broadcast(RoutePutMessage jo)
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

    public void setProperty(String key, Object value)
    {
        this.properties.put(key, value);
        this.saveChannelProperties();
    }

    public void removeProperty(String key)
    {
        this.properties.remove(key);
        this.saveChannelProperties();
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

    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("name", this.name);
        jo.put("lastAccess", this.lastAccess);
        jo.put("idle", this.getIdle());
        jo.put("members", this.membersAsJSONObject());
        jo.put("memberCount", this.memberCount());
        jo.put("properties", this.getProperties());
        jo.put("permanent", this.permanent);
        jo.put("msgTxPerSecond", this.msgTxPerSecond);
        jo.put("msgRxPerSecond", this.msgRxPerSecond);
        if (this.collector != null)
        {
            jo.put("collector", this.collector.getConnectionId());
        }
        return jo;
    }
}