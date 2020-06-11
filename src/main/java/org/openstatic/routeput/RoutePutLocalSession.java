package org.openstatic.routeput;

import org.json.*;
import java.util.Vector;

public class RoutePutLocalSession implements RoutePutSession
{
    private RoutePutServerWebsocket serverWebsocket;
    protected String defaultChannel = "*";
    private Vector<String> channels = new Vector<String>();
    private String path;
    private String remoteIP;
    protected String connectionId;
    private boolean collector = false;
    private JSONObject upgradeHeaders;
    private boolean connected;

    public RoutePutLocalSession(RoutePutServerWebsocket rpsw, String connectionId)
    {
        this.connectionId = connectionId;
        this.serverWebsocket = rpsw;
        this.defaultChannel = rpsw.getDefaultChannel();
    }

    protected void handleMessage(RoutePutMessage jo)
    {
        if (!jo.has("__eventChannel"))
        {
            jo.put("__eventChannel", this.defaultChannel);
        }
        if (jo.has("__sourceConnectStatus"))
        {
            this.connected = jo.optBoolean("__sourceConnectStatus", false);
            this.upgradeHeaders = jo.optJSONObject("upgradeHeaders");
            this.remoteIP = jo.optString("remoteIp", null);
        }
        RoutePutServer.instance.handleIncomingEvent(jo, this);
    }

    public boolean becomeCollector()
    {
        if (!RoutePutServer.instance.collectors.containsKey(this.defaultChannel))
        {
            RoutePutServer.instance.collectors.put(this.defaultChannel, this);
            this.collector = true;
        }
        return this.collector;
    }
    
    // Main method for transmitting objects to client.
    public void send(JSONObject jo)
    {
        if (this.serverWebsocket != null && jo != null)
        {
            if (!jo.has("__eventChannel"))
            {
                jo.put("__eventChannel", this.defaultChannel);
            }
            if (!jo.has("__targetId"))
            {
                jo.put("__targetId", this.connectionId);
            }
            this.serverWebsocket.send(jo);
        }
    }

    @Override
    public boolean isCollector()
    {
        return this.collector;
    }

    @Override
    public boolean isConnected()
    {
        return this.serverWebsocket.isConnected() && this.connected;
    }
    
    public String getDefaultChannel()
    {
        return this.defaultChannel;
    }
    
    public void addChannel(String channel)
    {
        if (channel != null)
        {
            if (!this.channels.contains(channel))
                this.channels.add(channel);
        }
    }
    
    public void removeChannel(String channel)
    {
        if (channel != null)
        {
            if (this.channels.contains(channel))
              this.channels.remove(channel);
        }
    }
    
    public String getConnectionId()
    {
        return this.connectionId;
    }
    
    public boolean subscribedTo(String channel)
    {
        return (this.channels.contains(channel) ||      // Are we subscribed to the channel?
                this.defaultChannel.equals(channel) ||  // Is our default channel the channel?
                this.defaultChannel.equals("*"));       // Is our default channel * ?
    }
    
    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        jo.put("connectionId", this.connectionId);
        jo.put("collector", false);
        jo.put("defaultChannel", this.defaultChannel);
        jo.put("socketPath", this.path);
        jo.put("channels", new JSONArray(this.channels));
        jo.put("upgradeHeaders", this.upgradeHeaders);
        jo.put("remoteIP", this.remoteIP);
        jo.put("_class", "RoutePutLocalSession");
        return jo;
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
