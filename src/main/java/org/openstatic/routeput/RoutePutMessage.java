package org.openstatic.routeput;

import org.json.JSONObject;

public class RoutePutMessage extends JSONObject
{
    public RoutePutMessage(String json)
    {
        super(json);
    }

    public RoutePutMessage()
    {
        super();
    }

    public String getSourceId()
    {
        return this.optString("__sourceId", null);
    }

    public void setSourceId(String connectionId)
    {
        this.put("__sourceId", connectionId);
    }
    
    public String getTargetId()
    {
        return this.optString("__targetId", null);
    }

    public void setTargetId(String connectionId)
    {
        this.put("__targetId", connectionId);
    }
    
    public String getChannel()
    {
        return this.optString("__eventChannel", "*");
    }

    public void setChannel(String channel)
    {
        this.put("__eventChannel", channel);
    }

    public boolean isResponse()
    {
        return this.has("__response");
    }

    public String getResponse()
    {
        return this.optString("__response", "");
    }

    public void setResponse(String response)
    {
        this.put("__response", response);
    }

    public boolean isRequest()
    {
        return this.has("__request");
    }

    public String getRequest()
    {
        return this.optString("__request", "");
    }

    public void setRequest(String request)
    {
        this.put("__request", request);
    }
}