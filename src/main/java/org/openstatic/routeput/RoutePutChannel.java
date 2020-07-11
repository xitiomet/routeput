package org.openstatic.routeput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONObject;

public class RoutePutChannel
{
    private static HashMap<String, RoutePutChannel> channels;
    private static Thread channelTracker = null;

    private String name;
    private JSONObject properties;
    private ArrayList<RoutePutSession> members;
    private long lastAccess;
    private RoutePutSession collector;
    private int messagesTx;
    private int messagesRx;

    private int msgTxPerSecond;
    private int msgRxPerSecond;


    private RoutePutChannel(String name)
    {
        this.msgTxPerSecond = 0;
        this.msgRxPerSecond = 0;
        this.messagesTx = 0;
        this.messagesRx = 0;
        this.name = name;
        this.lastAccess = System.currentTimeMillis();
        this.members = new ArrayList<RoutePutSession>();
        this.collector = null;
        this.properties = new JSONObject();
    }

    public static Thread initTracker()
    {
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
        RoutePutChannel.channels.values().removeIf((c) -> { return (c.getIdle() > idleTimeout); });
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

    public void bumpTx()
    {
        this.messagesTx++;
        this.touch();
    }
    
    public void bumpRx()
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
        return this.members.contains(session);
    }

    public void addMember(RoutePutSession session)
    {
        if (!this.members.contains(session))
            this.members.add(session);
        this.touch();
    }

    public void removeMember(RoutePutSession session)
    {
        if (this.members.contains(session))
            this.members.remove(session);
        if (session == this.collector)
            this.collector = null;
        this.touch();
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

    public void transmitMembers(RoutePutSession session)
    {
        this.members.parallelStream().forEach((m) -> {
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

    public JSONObject membersAsJSONObject()
    {
        JSONObject jo = new JSONObject();
        for(RoutePutSession s: this.members)
        {
            jo.put(s.getConnectionId(), s.toJSONObject());
        }
        return jo;
    }

    public JSONArray membersAsJSONArray()
    {
        JSONArray ja = new JSONArray();
        for(RoutePutSession s: this.members)
        {
            ja.put(s.toJSONObject());
        }
        return ja;
    }

    public void setProperty(String key, Object value)
    {
        this.properties.put(key, value);
    }

    public void removeProperty(String key)
    {
        this.properties.remove(key);
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
        jo.put("msgTxPerSecond", this.msgTxPerSecond);
        jo.put("msgRxPerSecond", this.msgRxPerSecond);
        if (this.collector != null)
        {
            jo.put("collector", this.collector.getConnectionId());
        }
        return jo;
    }
}