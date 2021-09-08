package org.openstatic.routeput.midi;

import org.openstatic.routeput.*;

import javax.sound.midi.*;
import org.json.*;

public class RouteputMIDIReceiver implements Receiver
{
    private RoutePutChannel channel;
    private RoutePutSession session;
    private int beatPulse;
    private long lastTimeStamp;

    public RouteputMIDIReceiver(RoutePutSession session, RoutePutChannel channel)
    {
        this.channel = channel;
        this.beatPulse = 0;
        this.session = session;
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

    }
    
    public void send(MidiMessage message, long timeStamp)
    {
        this.lastTimeStamp = timeStamp;
        if(message instanceof ShortMessage && this.session.isConnected())
        {
            final ShortMessage sm = (ShortMessage) message;
            int smStatus = sm.getStatus();
            if (smStatus == ShortMessage.TIMING_CLOCK)
            {
                sendPulse(timeStamp);
            } else {
                RoutePutMessage mm = new RoutePutMessage();
                mm.setType(RoutePutMessage.TYPE_MIDI);
                JSONArray dArray = new JSONArray();
                dArray.put(sm.getStatus());
                dArray.put(sm.getData1());
                dArray.put(sm.getData2());
                mm.setMetaField("data", dArray);
                mm.setMetaField("ts", timeStamp);
                mm.setChannel(this.channel);
                this.session.send(mm);
            }
        }
    }

    private void sendPulse(long timeStamp)
    {
        //System.err.println("timing");
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
    
    public String toString()
    {
        return this.getName();
    }

}
