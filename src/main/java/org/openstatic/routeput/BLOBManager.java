package org.openstatic.routeput;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;

import org.eclipse.jetty.http.MimeTypes;
import org.json.JSONObject;

public class BLOBManager 
{
    private static HashMap<String, StringBuffer> blobStorage;

    public static void init()
    {
        if (BLOBManager.blobStorage == null)
        {
            BLOBManager.blobStorage = new HashMap<String, StringBuffer>();
        }
    }

    public static void handleBlobData(RoutePutMessage jo)
    {
        BLOBManager.init();
        if (jo.hasMetaField("i") && jo.hasMetaField("of") && jo.hasMetaField("data") && jo.hasMetaField("name"))
        {
            JSONObject rpm = jo.getRoutePutMeta();
            int i = rpm.optInt("i", 0);
            int of = rpm.optInt("of", 0);
            String name = rpm.optString("name", "");
            StringBuffer sb;
            if (i == 1)
            {
                sb = new StringBuffer();
                BLOBManager.blobStorage.put(name, sb);
            } else {
                sb = BLOBManager.blobStorage.get(name);
            }
            sb.append(rpm.optString("data",""));
            if (i == of)
            {
                File blobFolder = jo.getRoutePutChannel().getBlobFolder();
                if (blobFolder != null)
                {
                    File blobFile = new File(blobFolder, name);
                    BLOBManager.saveBase64Blob(blobFile, sb);
                    BLOBManager.blobStorage.remove(name);
                }
                /*
                RoutePutServer.logIt("Received Blob: " + name +
                        " on " + jo.optString("__eventChannel", this.defaultChannel.getName()) +
                        " from " + this.getConnectionId());*/

                        /*
                RoutePutMessage resp = new RoutePutMessage();
                resp.setResponse("blob");
                resp.put("name", name);
                this.send(resp);
                */
            }
        }
    }

    public static void sendBlob(RoutePutSession session, String name)
    {
        File blobFolder = session.getDefaultChannel().getBlobFolder();
        if (blobFolder != null)
        {
            File blobFile = new File(blobFolder, name);
            StringBuffer sb = BLOBManager.loadBase64Blob(blobFile);
            transmitBlobChunks(session, name, sb);
        }
    }

    // Send a chunked blob to client from byte array
    public static void sendBlob(RoutePutSession session, String name, String contentType, byte[] bytes)
    {
        StringBuffer sb = new StringBuffer();
        sb.append("data:" + contentType + ";base64,");
        sb.append(java.util.Base64.getEncoder().encodeToString(bytes));
        transmitBlobChunks(session, name, sb);
    }
    
    // Transmit a blob to this session shouldnt be called except by other sendBlob methods
    private static void transmitBlobChunks(final RoutePutSession session, final String name, final StringBuffer sb)
    {
        Thread x = new Thread(() -> {
            int size = sb.length();
            int chunkSize = 4096;
            int numChunks = (size + chunkSize - 1) / chunkSize;
            for (int i = 0; i < numChunks; i++)
            {
                RoutePutMessage mm = new RoutePutMessage();
                mm.setType("blob");
                mm.setMetaField("name", name);
                mm.setMetaField("i", i+1);
                mm.setMetaField("of", numChunks);
                int start = i*chunkSize;
                int end = start + chunkSize;
                if (end > size)
                    end = size;
                mm.setMetaField("data", sb.substring(start,end));
                session.send(mm);
            }
        });
        x.start();
    }

    public static File saveBase64Blob(File file, StringBuffer sb)
    {
        try
        {
            byte[] fileData = java.util.Base64.getDecoder().decode(sb.substring(sb.indexOf(",") + 1));
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(fileData);
            fos.close();
            return file;
        } catch (Exception e) {
            //logIt(e);
        }
        return null;
    }
    
    public static StringBuffer loadBase64Blob(File file)
    {
        StringBuffer sb = new StringBuffer();
        try
        {
            String contentType = getContentTypeFor(file.getName());
            if (file.exists())
            {
                sb.append("data:" + contentType + ";base64,");
                FileInputStream fis = new FileInputStream(file);
                byte[] bFile = new byte[(int) file.length()];
                fis.read(bFile);
                fis.close();
                sb.append(java.util.Base64.getEncoder().encodeToString(bFile));
            }
        } catch (Exception e) {
            //logIt(e);
        }
        return sb;
    }

    /** Determine the content type of a local file */
    public static String getContentTypeFor(String filename)
    {
        String lc_file = filename.toLowerCase();
        if (lc_file.endsWith(".html") || lc_file.endsWith(".htm"))
        {
            return "text/html";
        } else if (lc_file.endsWith(".txt")) {
            return "text/plain";
        } else if (lc_file.endsWith(".css")) {
            return "text/css";
        } else if (lc_file.endsWith(".js")) {
            return "text/javascript";
        } else if (lc_file.endsWith(".jpg") || lc_file.endsWith(".jpe") || lc_file.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lc_file.endsWith(".gif")) {
            return "image/gif";
        } else if (lc_file.endsWith(".png")) {
            return "image/png";
        } else if (lc_file.endsWith(".bmp")) {
            return "image/x-ms-bmp";
        } else if (lc_file.endsWith(".mp3")) {
            return "audio/mpeg3";
        } else if (lc_file.endsWith(".zip")) {
            return "application/zip";
        } else if (lc_file.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lc_file.endsWith(".xml")) {
            return "text/xml";
        } else if (lc_file.endsWith(".mid")) {
            return "audio/midi";
        } else if (lc_file.endsWith(".tar")) {
            return "application/x-tar";
        } else if (lc_file.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lc_file.endsWith(".avi")) {
            return "video/x-msvideo";
        } else if (lc_file.endsWith(".mp4")) {
            return "video/mp4";
        } else if (lc_file.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (lc_file.endsWith(".mov")) {
            return "video/quicktime";
        } else if (lc_file.endsWith(".wmv")) {
            return "video/x-ms-wmv";
        } else if (lc_file.endsWith(".3gp")) {
            return "video/3gpp";
        } else {
            String result = MimeTypes.getDefaultMimeByExtension(filename);
            if ("".equals(result) || result == null)
                result = "application/octet-stream";
            return result;
        }
    }
}