package org.openstatic.routeput;

import org.openstatic.routeput.util.JSONTools;
import java.util.Random;
import java.util.StringTokenizer;

import org.json.JSONArray;
import org.json.JSONObject;

public class RoutePutMessage extends JSONObject
{
    // This is the first message exchanged by a client/server to start the connection
    public static final String TYPE_CONNECTION_ID = "connectionId";

    // This message is for letting a channel know you've joined or left a channel this message should always travel the network
    // to let all servers know that a user has joined a channel
    public static final String TYPE_CONNECTION_STATUS = "ConnectionStatus";

    // For Big large object, images, data to transfer from server to server. Think of it as files everyone wants to share
    public static final String TYPE_BLOB = "blob";
    public static final String TYPE_PROPERTY_CHANGE = "propertyChange";

    // For sending midi messages over routeput
    public static final String TYPE_MIDI = "midi";
    public static final String TYPE_PULSE = "pulse";

    // For sending GPS data over routeput
    public static final String TYPE_GPS = "gps";

    // These messages should NEVER travel, they are simply for making requests between two endpoints
    public static final String TYPE_REQUEST = "request";
    public static final String TYPE_RESPONSE = "response";

    // Pretty self explanitory.
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";

    // If somebody throws an error in the stream, everyone should know about it
    public static final String TYPE_LOG_ERROR = "error";
    public static final String TYPE_LOG_WARNING = "warning";
    public static final String TYPE_LOG_INFO = "info";

    // For Binary streams inside a channel
    public static final String TYPE_BINARY_STREAM = "binary";

    /* Create a routeput message from a JSONObject */
    public RoutePutMessage(JSONObject jsonObject)
    {
        super(jsonObject.toString());
        getRoutePutMeta();
    }

    /* Create a routeput message from a json string */
    public RoutePutMessage(String json)
    {
        super(json);
        getRoutePutMeta();
    }

    /* Create an empty routeput message */
    public RoutePutMessage()
    {
        super();
        getRoutePutMeta();
    }

    public static synchronized String generateMessageId()
    {
        int key_length = 10;
        try
        {
            Thread.sleep(1);
        } catch (Exception e) {}
        Random n = new Random(System.currentTimeMillis());
        String alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuffer return_key = new StringBuffer();
        for (int i = 0; i < key_length; i++)
        {
            return_key.append(alpha.charAt(n.nextInt(alpha.length())));
        }
        String randKey = return_key.toString();
        return randKey;
    }

    /* return the contents of the "__routeput" field as a JSONObject */
    public JSONObject getRoutePutMeta()
    {
        if (!this.has("__routeput"))
        {
            JSONObject rpm = new JSONObject();
            rpm.put("msgId", generateMessageId());
            this.put("__routeput", rpm);
            return rpm;
        } else {
            JSONObject rpm = this.optJSONObject("__routeput");
            if (!rpm.has("msgId"))
            {
                rpm.put("msgId", generateMessageId());
            }
            return rpm;
        }
    }

    public void mergeRouteputMeta(JSONObject obj)
    {
        if (obj != null)
        {
            for(String key : obj.keySet())
            {
                Object newValue = obj.opt(key);
                this.getRoutePutMeta().put(key, newValue);
            }
        }
    }

    public  boolean hasMetaField(String fieldName)
    {
        return this.getRoutePutMeta().has(fieldName);
    }

    public void setMetaField(String fieldName, int val)
    {
        this.getRoutePutMeta().put(fieldName, val);
    }

    public void setMetaField(String fieldName, long val)
    {
        this.getRoutePutMeta().put(fieldName, val);
    }

    public void setMetaField(String fieldName, boolean val)
    {
        this.getRoutePutMeta().put(fieldName, val);
    }

    public void setMetaField(String fieldName, Object val)
    {
        this.getRoutePutMeta().put(fieldName, val);
    }

    public boolean optMetaField(String fieldName, boolean defaultValue)
    {
        return this.getRoutePutMeta().optBoolean(fieldName, defaultValue);
    }

    public String optMetaField(String fieldName, String defaultValue)
    {
        return this.getRoutePutMeta().optString(fieldName, defaultValue);
    }

    public void removeMetaField(String fieldName)
    {
        if (this.hasMetaField(fieldName))
        {
            this.getRoutePutMeta().remove(fieldName);
        }
    }

    public void appendMetaArray(String fieldName, Object val)
    {
        if (this.hasMetaField(fieldName))
        {
            this.getRoutePutMeta().optJSONArray(fieldName).put(val);
        } else {
            JSONArray ary = new JSONArray();
            ary.put(val);
            this.setMetaField(fieldName, ary);
        }
    }

    public Object getPathValue(String path)
    {
        if (!"".equals(path) && path != null)
        {
            Object pathValue = JSONTools.getPathValue(this, path);
            if (pathValue == null)
                return path;
            else
                return pathValue;
        } else {
            return this.toCleanJSONObject();
        }
    }

    public String getMessageId()
    {
        return this.getRoutePutMeta().optString("msgId", null);
    }

    // Mark this message as a response to another message
    public void setRef(RoutePutMessage msg)
    {
        if (msg != null)
        {
            this.getRoutePutMeta().put("ref", msg.getMessageId());
        }
    }

    public String getSourceId()
    {
        return this.getRoutePutMeta().optString("srcId", null);
    }

    public void setSourceId(String connectionId)
    {
        this.getRoutePutMeta().put("srcId", connectionId);
    }

    public void setSourceIdIfNull(String connectionId)
    {
        if (!this.getRoutePutMeta().has("srcId"))
        {
            this.getRoutePutMeta().put("srcId", connectionId);
        }
    }

    public void setSource(RoutePutSession session)
    {
        if (session != null)
        {
            this.getRoutePutMeta().put("srcId", session.getConnectionId());
        }
    }

    public boolean hasSourceId()
    {
        return this.hasMetaField("srcId");
    }
    
    public String getTargetId()
    {
        return this.getRoutePutMeta().optString("dstId", null);
    }

    public void setTarget(RoutePutSession session)
    {
        if (session != null)
        {
            this.setTargetId(session.getConnectionId());
        }
    }

    public void setTargetId(String connectionId)
    {
        this.getRoutePutMeta().put("dstId", connectionId);
    }

    public RoutePutMessage forTarget(RoutePutSession session)
    {
        RoutePutMessage newMsg = new RoutePutMessage(this);
        newMsg.setTargetId(session.getConnectionId());
        return newMsg;
    }

    public boolean hasTargetId()
    {
        return this.hasMetaField("dstId");
    }

    public String getChannel()
    {
        return this.getRoutePutMeta().optString("channel", null);
    }

    public RoutePutChannel getRoutePutChannel()
    {
        return RoutePutChannel.getChannel(this.getChannel());
    }

    public boolean hasChannel()
    {
        return this.hasMetaField("channel");
    }

    public void setChannel(String channel)
    {
        this.getRoutePutMeta().put("channel", channel);
    }

    public void setChannel(RoutePutChannel channel)
    {
        this.getRoutePutMeta().put("channel", channel.getName());
    }

    public void setChannelIfNull(RoutePutChannel channel)
    {
        if (!this.getRoutePutMeta().has("channel"))
        {
            this.getRoutePutMeta().put("channel", channel);
        }
    }

    public boolean isType(String type)
    {
        if (type != null)
            return type.equals(this.getRoutePutMeta().optString("type", null));
        else
            return false;
    }

    public String getType()
    {
        return this.getRoutePutMeta().optString("type", null);
    }

    public void setType(String type)
    {
        this.getRoutePutMeta().put("type", type);
    }

    public void setLogged(boolean logged)
    {
        this.getRoutePutMeta().put("log", logged);
    }

    public boolean canBeLogged()
    {
        return this.getRoutePutMeta().optBoolean("log", true);
    }

    public void setRequest(String requestType)
    {
        this.setType(TYPE_REQUEST);
        this.getRoutePutMeta().put("request", requestType);
    }

    public void setResponse(String responseType, RoutePutMessage request)
    {
        this.setType(TYPE_RESPONSE);
        this.getRoutePutMeta().put("response", responseType);
        this.setChannel(request.getRoutePutChannel());
        this.setRef(request);
    }

    public String getRequest()
    {
        return this.getRoutePutMeta().optString("request","");
    }

    public String getResponse()
    {
        return this.getRoutePutMeta().optString("response","");
    }

    public JSONObject toCleanJSONObject()
    {
        JSONObject jo = new JSONObject();
        try
        {
            JSONArray names = this.names();
            for(int i = 0; i < names.length(); i++)
            {
                String name = names.getString(i);
                if (!"__routeput".equals(name))
                {
                    Object value = this.get(name);
                    jo.put(name, value);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return jo;
    }
}