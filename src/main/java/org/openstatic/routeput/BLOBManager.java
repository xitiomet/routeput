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
    private static File blobRoot;
    public static JSONObject settings = new JSONObject();

    public static File getBlobRoot()
    {
        return BLOBManager.blobRoot;
    }

    public static void init(JSONObject settings)
    {
        if (settings != null)
        {
            BLOBManager.settings = settings;
        }
        if (BLOBManager.blobRoot == null)
        {
            BLOBManager.blobRoot = new File(BLOBManager.settings.optString("blobStorageRoot", "./blob/"));
            if (!BLOBManager.blobRoot.exists())
            {
                BLOBManager.blobRoot.mkdir();
            }
        }
        if (BLOBManager.blobStorage == null)
        {
            BLOBManager.blobStorage = new HashMap<String, StringBuffer>();
        }
    }

    public static void handleBlobData(RoutePutSession session, RoutePutMessage jo)
    {
        BLOBManager.init(null);
        if (jo.hasMetaField("i") && jo.hasMetaField("of") && jo.hasMetaField("data") && jo.hasMetaField("name"))
        {
            JSONObject rpm = jo.getRoutePutMeta();
            String context = rpm.optString("context", null);

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
                File blobFolder = null;
                if (context == null && jo.getRoutePutChannel() != null)
                {
                    RoutePutChannel chan = jo.getRoutePutChannel();
                    blobFolder = chan.getBlobFolder();
                    context = "channel." + chan.getName();
                } else {
                    blobFolder = new File(BLOBManager.blobRoot, context);
                    if (!blobFolder.exists())
                    {
                        blobFolder.mkdir();
                    }
                }
                if (blobFolder != null)
                {
                    BLOBFile blobFile = new BLOBFile(blobFolder, context, name);
                    BLOBManager.saveBase64Blob(blobFile, sb);
                    BLOBManager.blobStorage.remove(name);
                    // Acknowledge blob sent
                    RoutePutMessage resp = new RoutePutMessage();
                    resp.setType(RoutePutMessage.TYPE_BLOB);
                    resp.mergeRouteputMeta(blobFile.toJSONObject());
                    resp.setRef(jo);
                    if (jo.hasChannel())
                    {
                        resp.setChannel(jo.getRoutePutChannel());
                        jo.getRoutePutChannel().onMessage(null, resp);
                    } else {
                        session.send(resp);
                    }
                }
            }
        }
    }

    public static BLOBFile resolveBlob(String context, String name)
    {
        File blobFolder = BLOBManager.blobRoot;
        if (context != null)
        {
            blobFolder = new File(BLOBManager.blobRoot, context);
            if (!blobFolder.exists())
            {
                blobFolder.mkdir();
            }
        }
        if (blobFolder != null)
        {
            BLOBFile blobFile = new BLOBFile(blobFolder, context, name);
            return blobFile;
        }
        return null;
    }

    public static boolean blobExists(String context, String name)
    {
        File blobFile = resolveBlob(context, name);
        if (blobFile != null)
        {
            return blobFile.exists();
        }
        return false;
    }

    public static void fetchBlob(RoutePutSession session, RoutePutMessage request)
    {
        JSONObject rpm = request.getRoutePutMeta();
        String name = rpm.optString("name", "");
        String context = rpm.optString("context");
        RoutePutChannel channel = request.getRoutePutChannel();
        BLOBFile blobFile = resolveBlob(context, name);
        if (blobFile != null)
        {
            if (blobFile.exists())
            {
                StringBuffer sb = blobFile.getBase64StringBuffer();
                transmitBlobChunks(session, name, context, sb, request);
            } else {
                RoutePutMessage resp = new RoutePutMessage();
                resp.setType(RoutePutMessage.TYPE_BLOB);
                resp.setRef(request);
                resp.setMetaField("name", name);
                resp.setChannel(channel);
                if (context != null)
                {
                    resp.setMetaField("context", context);
                }
                resp.setMetaField("exists", false);
                session.send(resp);
            }
        } else {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setType(RoutePutMessage.TYPE_BLOB);
            resp.setRef(request);
            resp.setMetaField("name", name);
            resp.setChannel(channel);
            if (context != null)
            {
                resp.setMetaField("context", context);
            }
            resp.setMetaField("exists", false);
            session.send(resp);
        }
    }

    // Send a chunked blob to client from byte array
    public static void sendBlob(RoutePutSession session, String name, final String context, String contentType, byte[] bytes)
    {
        StringBuffer sb = new StringBuffer();
        sb.append("data:" + contentType + ";base64,");
        sb.append(java.util.Base64.getEncoder().encodeToString(bytes));
        transmitBlobChunks(session, name, context, sb, null);
    }
    
    // Transmit a blob to this session shouldnt be called except by other sendBlob methods
    private static void transmitBlobChunks(final RoutePutSession session, final String name, final String context, final StringBuffer sb, final RoutePutMessage request)
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
                if (context != null)
                {
                    mm.setMetaField("context", context);
                }
                mm.setMetaField("i", i+1);
                mm.setMetaField("of", numChunks);
                if ((i + 1) == numChunks && request != null)
                {
                    mm.setRef(request);
                }
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