package org.openstatic.routeput;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.json.JSONObject;

public class BLOBFile extends File
{
    private String context;
    
    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes)
    {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public BLOBFile(File containingFolder, String context, String name)
    {
        super(containingFolder, name);
        this.context = context;
    }

    public String getURL()
    {
        try
        {
            String fallbackApiPath = "http://" + RoutePutChannel.getHostname() + ":" + String.valueOf(BLOBManager.settings.optInt("port", 6144)) + BLOBManager.settings.optString("apiMountPath", "/api/*").replace("*", "");
            String apiPath = BLOBManager.settings.optString("fullApiMountPath", fallbackApiPath);
            if (this.context != null)
            {
                if (this.context.startsWith("channel."))
                {
                    String channelName = this.context.substring(8);
                    return apiPath + "channel/" + URLEncoder.encode(channelName, "UTF-8") + "/blob/" + URLEncoder.encode(this.getName(), "UTF-8");
                } else {
                    return apiPath + "blob/" + URLEncoder.encode(this.context, "UTF-8") + "/" + URLEncoder.encode(this.getName(), "UTF-8");
                }
            } else {
                return apiPath + "blob/" + URLEncoder.encode(this.getName(), "UTF-8");
            }
        } catch (Exception e) {
            RoutePutServer.logError(e);
            return null;
        }
    }

    private static String generateMD5(File file)
    {
        //this.cancel_operation = false;
        FileInputStream inputStream = null;
        try
        {
            inputStream = new FileInputStream(file);
        } catch (Exception fnfe) {
            return null;
        }
        if(inputStream==null)
        {
            return null;
        }
        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("MD5");
            FileChannel channel = inputStream.getChannel();
            ByteBuffer buff = ByteBuffer.allocate(2048);
            int amount = 0;
            int bytes_read = 0;
            boolean continue_op = true;
            while(((bytes_read = channel.read(buff)) != -1) && continue_op)
            {
                buff.flip();
                md.update(buff);
                buff.clear();
                amount += bytes_read;
            }
            byte[] hashValue = md.digest();
            return bytesToHex(hashValue);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace(System.err);
            return null;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return null;
        } finally {
            try
            {
                if(inputStream!=null)
                    inputStream.close();
            } catch (IOException e) {

            }
        }
    }

    public String getContentType()
    {
        return BLOBManager.getContentTypeFor(this.getName());
    }

    public StringBuffer getBase64StringBuffer()
    {
        StringBuffer sb = new StringBuffer();
        try
        {
            String contentType = this.getContentType();
            if (this.exists())
            {
                sb.append("data:" + contentType + ";base64,");
                FileInputStream fis = new FileInputStream(this);
                byte[] bFile = new byte[(int) this.length()];
                fis.read(bFile);
                fis.close();
                sb.append(java.util.Base64.getEncoder().encodeToString(bFile));
            }
        } catch (Exception e) {
            //logIt(e);
        }
        return sb;
    }

    public String getMD5()
    {
        return generateMD5(this);
    }

    public JSONObject toJSONObject()
    {
        JSONObject jo = new JSONObject();
        boolean exists = this.exists();
        jo.put("name", this.getName());
        if (this.context != null)
        {
            jo.put("context", this.context);
        }
        jo.put("exists", exists);
        if (exists)
        {
            jo.put("size", this.length());
            jo.put("url", this.getURL());
            if (!this.isDirectory())
            {
                jo.put("contentType", this.getContentType());
            } else {
                jo.put("directory", true);
            }
        }
        return jo;
    }
    
}
