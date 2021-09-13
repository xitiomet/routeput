package org.openstatic.routeput.midi;

import org.openstatic.routeput.*;

import javax.lang.model.util.ElementScanner6;
import javax.sound.midi.*;
import org.json.*;

public class RouteputMIDIReceiver implements Receiver
{
    private RoutePutChannel channel;
    private RoutePutSession session;
    private int beatPulse;
    private long lastTimeStamp;
    private boolean enableTimestamps;
    private boolean opened;

    public RouteputMIDIReceiver(RoutePutSession session, RoutePutChannel channel)
    {
        this.channel = channel;
        this.beatPulse = 1;
        this.session = session;
        this.enableTimestamps = true;
        this.opened = true;
    }

    public void setEnableTimestamps(boolean v)
    {
        this.enableTimestamps = v;
    }
    
    public long getMicrosecondPosition()
    {
        return this.lastTimeStamp;
    }

    public boolean isOpened()
    {
        return this.session.isConnected();
    }

    public String getName()
    {
        return this.channel.getName();
    }

    public void close()
    {
        this.opened = false;
    }
    
    public void send(MidiMessage message, long timeStamp)
    {
        if(message instanceof ShortMessage && this.session.isConnected() && this.opened)
        {
            final ShortMessage sm = (ShortMessage) message;
            int smStatus = sm.getStatus();
            if (smStatus == ShortMessage.TIMING_CLOCK)
            {
                sendPulse(timeStamp);
            } else {
                this.lastTimeStamp = timeStamp;
                RoutePutMessage mm = new RoutePutMessage();
                mm.setType(RoutePutMessage.TYPE_MIDI);
                JSONArray dArray = new JSONArray();
                dArray.put(sm.getStatus());
                dArray.put(sm.getData1());
                dArray.put(sm.getData2());
                mm.setMetaField("data", dArray);
                if (timeStamp >= 0 && this.enableTimestamps)
                    mm.setMetaField("ts", timeStamp);
                mm.setChannel(this.channel);
                this.session.send(mm);
            }
        }
    }

    public void sendPulse(long timeStamp)
    {
        if (this.opened)
        {
            //System.err.println("timing");
            this.lastTimeStamp = timeStamp;
            RoutePutMessage mm = new RoutePutMessage();
            mm.setType(RoutePutMessage.TYPE_PULSE);
            mm.setMetaField("ts", timeStamp);
            mm.setMetaField("pulse", this.beatPulse);
            if (this.beatPulse >= 24)
            {
                this.beatPulse = 0;
            }
            this.beatPulse++;
            mm.setChannel(this.channel);
            this.session.send(mm);
        }
    }
    
    public String toString()
    {
        return this.getName();
    }

}
