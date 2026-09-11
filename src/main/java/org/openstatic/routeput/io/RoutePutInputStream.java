package org.openstatic.routeput.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.openstatic.routeput.RoutePutMessage;
import org.openstatic.routeput.RoutePutMessageListener;
import org.openstatic.routeput.RoutePutSession;

public class RoutePutInputStream extends InputStream implements RoutePutMessageListener 
{
    private final ByteBuffer buffer;
    private volatile boolean closed;

    public RoutePutInputStream() 
    {
        this.buffer = ByteBuffer.allocate(524288);
        // start in read-mode empty so hasRemaining() reflects real data availability
        this.buffer.flip();
        this.closed = false;
    }

    @Override
    public int read() throws IOException 
    {
        synchronized(this)
        {
            while (!this.buffer.hasRemaining())
            {
                if (this.closed) return -1;
                try
                {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("read interrupted", e);
                }
            }
            return this.buffer.get() & 0xFF;
        }
    }

    @Override
    public int read(byte[] b) throws IOException
    {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        if (len == 0) return 0;
        synchronized(this)
        {
            while (!this.buffer.hasRemaining())
            {
                if (this.closed) return -1;
                try
                {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("read interrupted", e);
                }
            }
            int bytesRead = Math.min(len, this.buffer.remaining());
            this.buffer.get(b, off, bytesRead);
            return bytesRead;
        }
    }

    @Override
    public int available() throws IOException 
    {
        synchronized(this)
        {
            return this.buffer.remaining();
        }
    }

    @Override
    public void close() throws IOException
    {
        synchronized(this)
        {
            this.closed = true;
            this.notifyAll();
        }
        super.close();
    }

    @Override
    public void onMessage(RoutePutSession session, RoutePutMessage message)
    {
        if(message.isType(RoutePutMessage.TYPE_BINARY_STREAM))
        {
            synchronized(this)
            {
                byte[] data = java.util.Base64.getDecoder().decode(message.optString("data",""));
                // switch from read-mode to write-mode, preserving any unread bytes
                this.buffer.compact();
                try
                {
                    this.buffer.put(data);
                } catch (Exception e) {
                    //System.err.println("Buffer overrun!");
                }
                this.buffer.flip();
                this.notifyAll();
            }
        }
    }
    
}