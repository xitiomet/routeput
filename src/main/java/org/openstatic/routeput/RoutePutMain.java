package org.openstatic.routeput;

import java.io.File;
import java.io.FileInputStream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import org.openstatic.routeput.client.*;
import org.openstatic.routeput.io.RoutePutInputStream;
import org.openstatic.routeput.io.RoutePutOutputStream;
import org.apache.commons.cli.*;
import org.json.*;

public class RoutePutMain
{
    
    public static void main(String[] args)
    {
        Thread channelTracker = RoutePutChannel.initTracker();
        RoutePutRemoteSession.init();
        //System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        //System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
        CommandLine cmd = null;
        JSONObject settings = new JSONObject();
        try
        {
            Options options = new Options();
            CommandLineParser parser = new DefaultParser();
            options.addOption(new Option("c", "config", true, "Config file location"));
            options.addOption(new Option("p", "port", true, "Specify HTTP port"));
            options.addOption(new Option("?", "help", false, "Shows help"));
            options.addOption(new Option("q", "quiet", false, "Quiet Mode"));
            options.addOption(new Option("x", "client", true, "Target URL to connect in test client"));
            options.addOption(new Option("m", "message", true, "Set Message for test client"));
            options.addOption(new Option("t", "test", true, "run named test mode"));


            Option upstreamOption = new Option("u", "upstream", true, "Connect to upstream server URL");
            upstreamOption.setOptionalArg(true);
            options.addOption(upstreamOption);
            
            Option channelOption = new Option("n", "channel", true, "Specify channel for --upstream or --client");
            channelOption.setOptionalArg(true);
            options.addOption(channelOption);

            cmd = parser.parse(options, args);
            
            if (!cmd.hasOption("q"))
            {
                System.err.println("  ______            _                   _   ");
                System.err.println("  | ___ \\          | |                 | |  ");
                System.err.println("  | |_/ /___  _   _| |_ ___ _ __  _   _| |_ ");
                System.err.println("  |    // _ \\| | | | __/ _ \\ '_ \\| | | | __|");
                System.err.println("  | |\\ \\ (_) | |_| | ||  __/ |_) | |_| | |_ ");
                System.err.println("  \\_| \\_\\___/ \\__,_|\\__\\___| .__/ \\__,_|\\__|");
                System.err.println("                           | |");
                System.err.println("                           |_|");
                System.err.println("");
                System.err.println("  Simple, Websocket Server and message router");
                System.err.println("");
            }
            
            if (cmd.hasOption("?"))
            {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp( "routeput", options );
                System.exit(0);
            }
            
            if (cmd.hasOption("c"))
            {
                File config = new File(cmd.getOptionValue('c',"routeput.json"));
                settings = RoutePutServer.loadJSONObject(config);
            }

            RoutePutChannel channel = null;
            String xTarget = "wss://openstatic.org/channel/";

            if (cmd.hasOption("n"))
            {
                channel = RoutePutChannel.getChannel(cmd.getOptionValue('n',"lobby"));
            }

            if (cmd.hasOption("m"))
            {
                settings.put("message", cmd.getOptionValue('m',"Hello World!"));
            }

            if (cmd.hasOption("p"))
            {
                int port = Integer.valueOf(cmd.getOptionValue('p',"6144")).intValue();
                settings.put("port", port);
            }

            if (cmd.hasOption("x"))
            {
                xTarget = cmd.getOptionValue('x',"wss://openstatic.org/channel/");
            }

            if (cmd.hasOption("t"))
            {
                String test = cmd.getOptionValue('t',"binary_tx");
                if ("binary_tx".equals(test))
                {
                    if (channel == null) channel = RoutePutChannel.getChannel("binary");
                    binaryTx(xTarget, channel);
                    System.exit(0);
                } else if ("binary_rx".equals(test)) {
                    if (channel == null) channel = RoutePutChannel.getChannel("binary");
                    binaryRx(xTarget, channel);
                    System.exit(0);
                } else if ("quote".equals(test)) {
                    if (channel == null) channel = RoutePutChannel.getChannel("lobby");
                    clientTest(xTarget, channel);
                    System.exit(0);
                } else if ("message".equals(test)) {
                    if (channel == null) channel = RoutePutChannel.getChannel("LoRa");
                    clientTest2(xTarget, channel, settings.optString("message", "Hello World!"));
                    System.exit(0);
                }
            }
            
            RoutePutServer rps = new RoutePutServer(settings);
            rps.setState(true);
            
            if (cmd.hasOption("u"))
            {
                if (channel == null) channel = RoutePutChannel.getChannel("*");
                rps.connectUpstream(channel, cmd.getOptionValue('u',"wss://openstatic.org/channel/"));
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        
    }

    public static void binaryTx(String url, RoutePutChannel channel)
    {
        RoutePutClient rpc = new RoutePutClient(channel, url);
        RoutePutOutputStream rpos = new RoutePutOutputStream(rpc);
        //PrintWriter pw = new PrintWriter(rpos);
        //RandomQuotes quotes = new RandomQuotes();
        rpc.connect();
        //int qid = 0;
                
        while(true)
        {
            try
            {
                File file = new File("sof.wav");
                AudioFileFormat aff = AudioSystem.getAudioFileFormat(file);
                AudioFormat af =  aff.getFormat();
                System.err.println("Sample Rate: " + String.valueOf(af.getSampleRate()));
                System.err.println("Sample Bits: " + String.valueOf(af.getSampleSizeInBits()));
                System.err.println("Channels: " + String.valueOf(af.getChannels()));
                System.err.println("Encoding: " + af.getEncoding().toString());
                System.err.println("Frame Size: " + String.valueOf(af.getFrameSize()));
                if (af.isBigEndian())
                    System.err.println("Big-Endian");
                else
                    System.err.println("Little-Endian");
                FileInputStream in = new FileInputStream(file);
                AudioInputStream aInputStream = new AudioInputStream(in, af, aff.getFrameLength());
                int count;
                byte buffer[] = new byte[2048];
                while ((count = aInputStream.read(buffer)) != -1)
                {
                    rpos.write(buffer, 0, count);
                    Thread.sleep(20);
                }
                in.close();
                System.err.println("Streamed sof.wav");
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public static void binaryRx(String url, RoutePutChannel channel) throws Exception
    {
        RoutePutClient rpc = new RoutePutClient(channel, url);
        rpc.connect();

        RoutePutInputStream rpis = new RoutePutInputStream();
        //BufferedInputStream bis = new BufferedInputStream(rpis);
        rpc.addMessageListener(rpis);

        SourceDataLine _speaker;
        AudioFormat _format = new AudioFormat(
            44100,  // Sample Rate
            16,     // Size of SampleBits
            1,      // Number of Channels
            true,   // Is Signed?
            false   // Is Big Endian?
        );

        //  creating the DataLine Info for the speaker format
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, _format);

        //  getting the mixer for the speaker
        _speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        _speaker.open(_format);
        _speaker.start();

        byte[] data = new byte[32];
        while(true)
        {
            try
            {
                if (rpis.available() > 128)
                {
                    //  count of the data bytes read 
                    int readCount = rpis.read(data, 0, data.length);

                    if(readCount > 0)
                    {
                        _speaker.write(data, 0, readCount);
                    }
                } else {
                    //System.err.println("Waiting for data....");
                    Thread.sleep(1);
                }
                
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public static void clientTest(String url, RoutePutChannel channel)
    {
        RoutePutClient rpc = new RoutePutClient(channel, url);
        channel.addChannelListener(new RoutePutChannelListener(){
        
            @Override
            public void onJoin(RoutePutChannel channel, RoutePutSession session) {
                if (session != rpc)
                {
                    System.err.println("Remote Session Connected: " + session.getConnectionId());
                    session.addMessageListener(new RoutePutMessageListener(){
                    
                        @Override
                        public void onMessage(RoutePutSession session, RoutePutMessage message) {
                            System.err.println(session.getConnectionId() + " Received " + message.toString());
                        }
                    });
                } else {
                    System.err.println("Local client connected");
                }
            }
            
            @Override
            public void onLeave(RoutePutChannel channel, RoutePutSession session) {
                if (session != rpc)
                {
                    System.err.println("Remote Session Disconnected: " + session.getConnectionId());
                } else {
                    System.err.println("Local client disconnected");
                }
            }
        });
        rpc.addMessageListener(new RoutePutMessageListener(){
                    
            @Override
            public void onMessage(RoutePutSession session, RoutePutMessage message) {
                System.err.println("CLIENT Received " + message.toString());
            }
        });
        rpc.setProperty("details", "random quote bot for testing");
        rpc.setProperty("username", "QuoteBot 5000");
        rpc.connect();
        //rpc.becomeCollector();
        RandomQuotes quotes = new RandomQuotes();
        try
        {
            while(rpc.isConnected())
            {
                RoutePutMessage msg = new RoutePutMessage();
                msg.put("event","chat");
                msg.put("text", quotes.nextQuote());
                msg.put("username", "Quote Of The Day");
                msg.setChannel(channel);
                System.err.println("Sending: " + msg.toString());
                rpc.send(msg);
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public static void clientTest2(String url, RoutePutChannel channel, String message)
    {
        RoutePutClient rpc = new RoutePutClient(channel, url);
        channel.addChannelListener(new RoutePutChannelListener() {
        
            @Override
            public void onJoin(RoutePutChannel channel, RoutePutSession session) {
                if (session != rpc)
                {
                    System.err.println("Remote Session Connected: " + session.getConnectionId());
                    session.addMessageListener(new RoutePutMessageListener(){
                    
                        @Override
                        public void onMessage(RoutePutSession session, RoutePutMessage message) {
                            System.err.println(session.getConnectionId() + " Received " + message.toString());
                        }
                    });
                } else {
                    System.err.println("Local client connected");
                }
            }
            
            @Override
            public void onLeave(RoutePutChannel channel, RoutePutSession session) {
                if (session != rpc)
                {
                    System.err.println("Remote Session Disconnected: " + session.getConnectionId());
                } else {
                    System.err.println("Local client disconnected");
                }
            }
        });
        rpc.addMessageListener(new RoutePutMessageListener(){
                    
            @Override
            public void onMessage(RoutePutSession session, RoutePutMessage message) {
                System.err.println("CLIENT Received " + message.toString());
            }
        });
        rpc.connect();
        //rpc.becomeCollector();
        try
        {
            while(rpc.isConnected())
            {
                RoutePutMessage msg = new RoutePutMessage();
                msg.setChannel(channel);
                msg.setSourceId(rpc.getConnectionId());
                msg.put("event","chat");
                msg.put("text", message);
                System.err.println("Sending: " + msg.toString());
                rpc.send(msg);
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}