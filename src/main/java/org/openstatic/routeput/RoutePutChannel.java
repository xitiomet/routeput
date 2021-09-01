package org.openstatic.routeput;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.routeput.util.JSONTools;

public class RoutePutChannel implements RoutePutMessageListener
{
    private static HashMap<String, RoutePutChannel> channels;
    private static Thread channelTracker = null;
    private static File channelRoot;
    private static String hostname;

    private PropertyChangeSupport propertyChangeSupport;
    private String name;
    private BufferedWriter logWriter;
    private JSONObject properties;
    private boolean unsavedProperties;
    protected LinkedHashMap<String, RoutePutSession> members;
    private long lastAccess;
    private RoutePutSession collector;
    private int messagesTx;
    private int messagesRx;
    private ArrayList<RoutePutChannelListener> listeners;
    private ArrayList<RoutePutMessageListener> messageListeners;

    private int msgTxPerSecond;
    private int msgRxPerSecond;

    /* Nobody should ever create a RoutePutChannel! it should always be pulled by static method getChannel() */
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
        this.unsavedProperties = false;
        File propertiesFile = this.getPropertiesFile();
        if (propertiesFile != null)
        {
            if (propertiesFile.exists())
            {
                this.properties = RoutePutServer.loadJSONObject(propertiesFile);
            }
        }
        if (this.properties.optBoolean("log", true))
        {
            try
            {
                File logFile = this.getLogFile();
                if (logFile != null)
                {
                    this.logWriter = new BufferedWriter(new FileWriter(logFile, true));
                }
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        }
    }

    /* Disable auto-disposing of channel */
    public void setPermanent(boolean v)
    {
        this.properties.put("permanent", true);
        saveChannelProperties();
    }

    /* will this channel be automatically deleted when idle */
    public boolean isPermanent()
    {
        return this.properties.optBoolean("permanent", false);
    }

    private void saveChannelProperties()
    {
        this.unsavedProperties = true;
    }

    /* returns the context string for storing blobs in this channel */
    public String getBLOBContext()
    {
        return "channel." + this.name;
    }

    public JSONArray getBlobs()
    {
        JSONArray ja = new JSONArray();
        File blobFolder = getBlobFolder();
        if (blobFolder != null)
        {
            ja = new JSONArray();

            String[] names = blobFolder.list();
            for (int i = 0; i < names.length; i++)
            {
                BLOBFile file = new BLOBFile(blobFolder, getBLOBContext(), names[i]);
                ja.put(file.toJSONObject());
            }
        }
        return ja;
    }

    /* Returns a java.io.File object representing the channel's blob storage folder */
    public File getBlobFolder()
    {
        File blobRoot = BLOBManager.getBlobRoot();
        if (blobRoot != null)
        {
            File blobFolder = new File(blobRoot, getBLOBContext());
            if (!blobFolder.exists())
            {
                blobFolder.mkdir();
            }
            return blobFolder;
        }
        return null;
    }

    /* Returns a java.io.File object representing the channel's property storage file */
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

    /* Returns a java.io.File object representing the channel's property storage file */
    private File getLogFile()
    {
        File channelFolder = this.getChannelFolder();
        if (channelFolder != null)
        {
            File channelPropertiesFile = new File(channelFolder, "channel.log");
            return channelPropertiesFile;
        } else {
            return null;
        }
    }

    /* Returns a java.io.File object representing the channel's data storage folder */
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

    /* Start the channel tracker's internal thread if it hasn't been started already */
    public static Thread initTracker()
    {
        if (RoutePutChannel.hostname == null)
        {
            try 
            {
                InetAddress ip;
                ip = InetAddress.getLocalHost();
                RoutePutChannel.hostname = ip.getHostName();
            } catch (Exception e) {}
        }

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
                    // sleep all channels
                    RoutePutChannel.channels.values().forEach((c) -> {
                        c.hibernate();
                    });
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
            try
            {
                if (c.logWriter != null)
                {
                    c.logWriter.flush();
                }
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        });
        long idleTimeout = 600l * 1000l;
        RoutePutChannel.channels.values().removeIf((c) -> {
            boolean removeIt = (c.getIdle() > idleTimeout) && c.memberCount() == 0 && !c.isPermanent();
            if (removeIt)
            {
                c.hibernate();
            } 
            return removeIt; 
        });
        RoutePutChannel.channels.values().stream().filter(c -> c.unsavedProperties).forEach((c) -> {
            try
            {
                RoutePutServer.saveJSONObject(c.getPropertiesFile(), c.properties);
                c.unsavedProperties = false;
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        });
    }

    /* Get the hostname of the routeput server, hostname is stored statically with RouteputChannel since it is the minimum
       requirement for a routeput implementation */
    public static String getHostname()
    {
        return RoutePutChannel.hostname;
    }

    public static void setHostname(String hostname)
    {
        RoutePutChannel.hostname = hostname;
    }

    /* How many messages left this channel per second */
    public int getMessagesTxPerSecond()
    {
        return this.msgTxPerSecond;
    }

    /* How many messages entered this channel per second */
    public int getMessagesRxPerSecond()
    {
        return this.msgRxPerSecond;
    }

    /* returns the channels name */
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

    protected void replaceMember(String connectionId, RoutePutSession oldSession, RoutePutSession newSession)
    {
        this.members.replace(connectionId, oldSession, newSession);
    }

    public boolean hasMember(String sessionId)
    {
        return this.members.containsKey(sessionId);
    }

    /* should always be called when a member joins, even if a remote member joins  */
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

    /* should always be called when a member leaves, even if a remote member leaves  */
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

    /* Transmit a connection status message to the session provided for every member of the channel */
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
                jo.setMetaField("remoteIP", m.getRemoteIP());
                jo.setMetaField("properties", m.getProperties());
                session.send(jo);
            }
        });
    }

    @Override
    public void onMessage(RoutePutSession session, RoutePutMessage j)
    {
        RoutePutChannel mChan = j.getRoutePutChannel();
        if (this.equals(mChan))
        {
            if (j.canBeLogged())
            {
                try
                {
                    if (this.logWriter != null && this.properties.optBoolean("log", true))
                    {
                        this.logWriter.write(j.toString() + "\n");
                    }
                } catch (Exception e) {
                    RoutePutServer.logError(e);
                }
            }
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

            // Trap setChannelProperty directives in the meta field and convert them into seperate messages
            if (j.hasMetaField("setChannelProperty"))
            {
                JSONObject storeRequest = j.getRoutePutMeta().optJSONObject("setChannelProperty");
                RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
                rppcm.setSource(session);
                for(String k : storeRequest.keySet())
                {
                    String v = storeRequest.getString(k);
                    Object oldValue = this.properties.opt(k);
                    Object newValue = j.getPathValue(v);
                    rppcm.addUpdate(this, k, oldValue, newValue);
                }
                j.removeMetaField("setChannelProperty");
                rppcm.processUpdates(session);
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
            } else if (j.isType(RoutePutMessage.TYPE_PROPERTY_CHANGE)) {
                RoutePutServer.logWarning("PROPERTY_CHANGE message hit channel " + this.name);
            } else if (this.hasCollector() && !j.isType(RoutePutMessage.TYPE_PROPERTY_CHANGE)) {
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
                l.onMessage(session, j);
            });
        } else {
            RoutePutServer.logWarning(this.getName() + " was asked to handle a packet that didnt belong to it! Intended for " + mChan.getName());
        }
    }

    /* transmit a message to all members of this channel, should be used internally only */
    protected void broadcast(RoutePutMessage jo)
    {
        if (jo.hasMetaField("where"))
        {
            JSONObject where = jo.getRoutePutMeta().optJSONObject("where");
            jo.removeMetaField("where");
            this.members.values().parallelStream()
                        .filter((s) -> JSONTools.matchesFilter(s.getProperties(), where))
                        .forEach((s) ->
            {
                try
                {
                    bumpTx();
                    s.send(jo.forTarget(s));
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            });
        } else {
            this.members.values().parallelStream()
                        .filter((s) -> s.isRootConnection())
                        .filter((s) -> !s.containsConnectionId(jo.getSourceId())) // Never Send the event to the creator or its relay
                        .forEach((s) ->
            {
                try
                {                    
                    bumpTx();
                    s.send(jo);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            });
        }
    }

    /* Returns a collection of the channels members */
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

    public void mergeProperties(JSONObject props)
    {
        if (props != null)
        {
            RoutePutPropertyChangeMessage setChannelPropertyMessage = new RoutePutPropertyChangeMessage();
            setChannelPropertyMessage.setChannel(this);
            for(String key : props.keySet())
            {
                Object oldValue = this.properties.opt(key);
                Object newValue = props.opt(key);
                setChannelPropertyMessage.addUpdate(this, key, oldValue, newValue);
                this.firePropertyChange(key, oldValue, newValue);
            }
            setChannelPropertyMessage.processUpdates(null);
        }
    }

    public void setProperty(String key, Object value)
    {
        Object oldValue = this.properties.opt(key);        
        RoutePutPropertyChangeMessage setChannelPropertyMessage = new RoutePutPropertyChangeMessage();
        setChannelPropertyMessage.addUpdate(this, key, oldValue, value);
        setChannelPropertyMessage.processUpdates(null);
    }

    protected void firePropertyChange(String key, Object oldValue, Object newValue)
    {
        if (newValue == null)
        {
            this.properties.remove(key);
        } else {
            this.properties.put(key, newValue);
        }
        this.propertyChangeSupport.firePropertyChange(key, oldValue, newValue);
        this.saveChannelProperties();
    }

    public void removeProperty(RoutePutSession session, String key)
    {
        Object oldValue = this.properties.opt(key);        
        RoutePutPropertyChangeMessage setChannelPropertyMessage = new RoutePutPropertyChangeMessage();
        setChannelPropertyMessage.addUpdate(this, key, oldValue, null);
        setChannelPropertyMessage.processUpdates(session);
    }

    public JSONObject getProperties()
    {
        return this.properties;
    }

    public static Collection<RoutePutChannel> getChannels()
    {
        return RoutePutChannel.channels.values();
    }

    /* static method for getting a channel object by its name, this should be the only way to retrieve a channel */
    public static synchronized RoutePutChannel getChannel(String name)
    {
        initTracker();
        if (name == null)
        {
            return null;
        } else {
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
    }

    /* Remove a session from all channels that exist in memory */
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

    private void hibernate()
    {
        RoutePutServer.saveJSONObject(this.getPropertiesFile(), this.properties);
        RoutePutServer.logIt("Channel \"" + this.getName() + "\" moved to cold storage, because of idle");
        if (this.logWriter != null)
        {
            try
            {
                this.logWriter.close();
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        }
    }

    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("name", this.name);
        jo.put("lastAccess", this.lastAccess);
        jo.put("idle", this.getIdle());
        jo.put("members", this.membersAsJSONObject());
        //jo.put("blobs", this.getBlobs());
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