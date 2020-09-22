package org.openstatic.routeput;

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

    public RoutePutMessage(JSONObject jsonObject)
    {
        super(jsonObject.toString());
        getRoutePutMeta();
    }

    public RoutePutMessage(String json)
    {
        super(json);
        getRoutePutMeta();
    }

    public RoutePutMessage()
    {
        super();
        getRoutePutMeta();
    }

    public JSONObject getRoutePutMeta()
    {
        if (!this.has("__routeput"))
        {
            JSONObject rpm = new JSONObject();
            this.put("__routeput", rpm);
            return rpm;
        } else {
            return this.optJSONObject("__routeput");
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
        Object ro = null;
        JSONObject pointer = this;
        if (!"".equals(path) && path != null)
        {
            StringTokenizer st = new StringTokenizer(path, ".");
            while(st.hasMoreTokens())
            {
                String pathNext = st.nextToken();
                ro = pointer.get(pathNext);
                if (ro instanceof JSONObject)
                    pointer = (JSONObject) ro;
            }
        } else {
            return this.toCleanJSONObject();
        }
        return ro;
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
        this.getRoutePutMeta().put("srcId", session.getConnectionId());
    }

    public boolean hasSourceId()
    {
        return this.getRoutePutMeta().has("srcId");
    }
    
    public String getTargetId()
    {
        return this.getRoutePutMeta().optString("dstId", null);
    }

    public void setTargetId(String connectionId)
    {
        this.getRoutePutMeta().put("dstId", connectionId);
    }

    public boolean hasTargetId()
    {
        return this.getRoutePutMeta().has("dstId");
    }

    
    public String getChannel()
    {
        return this.getRoutePutMeta().optString("channel", "*");
    }

    public RoutePutChannel getRoutePutChannel()
    {
        return RoutePutChannel.getChannel(this.getChannel());
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

    public void setRequest(String requestType)
    {
        this.setType(TYPE_REQUEST);
        this.getRoutePutMeta().put("request", requestType);
    }

    public void setResponse(String responseType)
    {
        this.setType(TYPE_RESPONSE);
        this.getRoutePutMeta().put("response", responseType);
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