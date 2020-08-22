package org.openstatic.routeput.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutMessageListener;

public class RoutePutInputStream extends InputStream implements RoutePutMessageListener 
{
    private ByteBuffer buffer;
    private boolean buffferWritten;

    public RoutePutInputStream() 
    {
        this.buffer = ByteBuffer.allocate(524288);
        this.buffer.clear();
        this.buffferWritten = false;
    }

    @Override
    public int read() throws IOException 
    {
        while (!this.buffer.hasRemaining())
        {
            try
            {
                this.wait();
            } catch (Exception e) {}
        }
        synchronized(this)
        {
            int byteToReturn = (int) this.buffer.get();
            return byteToReturn;
        }
    }

    @Override
    public int read(byte[] b) throws IOException
    {
        while (!this.buffer.hasRemaining())
        {
            try
            {
                this.wait();
            } catch (Exception e) {}
        }
        synchronized(this)
        {
            int bytesBefore = this.buffer.remaining();
            this.buffer.get(b);
            int bytesRead = bytesBefore - this.buffer.remaining();
            return bytesRead;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        while (!this.buffer.hasRemaining())
        {
            try
            {
                this.wait();
            } catch (Exception e) {}
        }
        synchronized(this)
        {
            int bytesBefore = this.buffer.remaining();
            this.buffer.get(b, off, len);
            int bytesRead = bytesBefore - this.buffer.remaining();
            return bytesRead;
        }
    }

    @Override
    public int available() throws IOException 
    {
        return this.buffer.remaining();
    }

    @Override
    public void onMessage(RoutePutMessage message)
    {
        if(message.isType(RoutePutMessage.TYPE_BINARY_STREAM))
        {
            synchronized(this)
            {
                byte[] data = java.util.Base64.getDecoder().decode(message.optString("data",""));
                if (this.buffferWritten)
                {
                    this.buffer.compact();
                } else {
                    this.buffferWritten = true;
                }
                try
                {
                    this.buffer.put(data);
                } catch (Exception e) {
                    //System.err.println("Buffer overrun!");
                }
                this.buffer.flip();
                this.notify();
            }
        }
    }
    
}