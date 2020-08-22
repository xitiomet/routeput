package org.openstatic.routeput.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.openstatic.routeput.RoutePutChannel;
import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutSession;

public class RoutePutOutputStream extends OutputStream implements Runnable
{
    private ByteArrayOutputStream baos;
    private RoutePutSession session;
    private Thread autoFlush;
    private boolean closed;
    private RoutePutChannel channel;
    private String targetId;

    public RoutePutOutputStream(RoutePutSession session)
    {
        this.baos = new ByteArrayOutputStream();
        this.session = session;
        this.channel = session.getDefaultChannel();
        this.closed = false;
        this.targetId = null;
        this.autoFlush = new Thread(this);
        this.autoFlush.start();
    }

    public RoutePutOutputStream(RoutePutSession session, RoutePutChannel channel)
    {
        this.baos = new ByteArrayOutputStream();
        this.session = session;
        this.channel = channel;
        this.closed = false;
        this.targetId = null;
        this.autoFlush = new Thread(this);
        this.autoFlush.start();
    }
    
    public void setTargetId(String connectionId)
    {
        this.targetId = connectionId;
    }

    @Override
    public void write(int b) throws IOException
    {
        synchronized(this)
        {
            this.baos.write(b);
        }
        if (this.baos.size() >= 256)
        {
            this.flush();
        }
    }
    
    @Override
    public void flush()
    {
        synchronized(this)
        {
            RoutePutMessage msg = new RoutePutMessage();
            msg.setType(RoutePutMessage.TYPE_BINARY_STREAM);
            msg.setChannel(this.channel);
            if (this.targetId != null)
            {
                msg.setTargetId(this.targetId);
            }
            msg.put("data", java.util.Base64.getEncoder().encodeToString(this.baos.toByteArray()));
            this.baos.reset();
            this.session.send(msg);
        }
    }

    @Override
    public void run()
    {
        while(!this.closed)
        {
            try
            {
                while (this.baos.size() == 0 && !this.closed)
                {
                    Thread.sleep(1500);
                }
                if (this.baos.size() > 0)
                {
                    this.flush();
                }
            } catch (Exception e) {

            }
        }
    }

    @Override
    public void close() throws IOException
    {
        super.close();
        this.closed = true;
    }
}