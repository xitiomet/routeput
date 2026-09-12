package org.openstatic.routeput;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import org.openstatic.routeput.client.*;
import org.openstatic.routeput.io.RoutePutInputStream;
import org.openstatic.routeput.io.RoutePutOutputStream;
import org.openstatic.routeput.util.RandomQuotes;
import org.apache.commons.cli.*;
import org.json.*;

public class RoutePutMain
{
    public static boolean keep_running;

    public static void main(String[] args)
    {
        RoutePutMain.keep_running = true;
        Runtime.getRuntime().addShutdownHook(new Thread() 
        { 
            public void run() 
            { 
                RoutePutMain.keep_running = false;
            } 
        });
        boolean serverMode = false;
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
            options.addOption(new Option("c", "config", true, "Config file location, also means server mode"));
            options.addOption(new Option("p", "port", true, "Specify HTTP port"));
            options.addOption(new Option("i", "binary-input-pipe", true, "Pipe raw standard input to a specific channel, using binary messages"));
            options.addOption(new Option("o", "binary-output-pipe", true, "Pipe raw standard output to a specific channel, using binary messages"));
            options.addOption(new Option("x", "binary-i-o-pipe", true, "Pipe raw standard input and output to a specific channel, using binary messages"));
            options.addOption(new Option("?", "help", false, "Shows help"));
            options.addOption(new Option("q", "quiet", false, "Quiet Mode"));
            //options.addOption(new Option("m", "message", true, "Set Message for test client"));
            //options.addOption(new Option("t", "test", true, "run named test mode"));


            Option upstreamOption = new Option("u", "upstream", true, "Create a bridge to another routeput server to link channels, or to specify a channel for a pipe operation Example: channel@ws://<server_channel_websocket_url>");
            upstreamOption.setOptionalArg(true);
            options.addOption(upstreamOption);
            
            Option channelOption = new Option("n", "channel", true, "Specify channel for --upstream or --client");
            channelOption.setOptionalArg(true);
            options.addOption(channelOption);
            RoutePutChannel channel = null;

            cmd = parser.parse(options, args);
    
            if (cmd.hasOption("n"))
            {
                channel = RoutePutChannel.getChannel(cmd.getOptionValue('n',"lobby"));
            } else {
                channel = RoutePutChannel.getChannel("lobby");
            }
            
            if (!cmd.hasOption("q") && cmd.hasOption("c"))
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
                System.err.println("  Simple websocket server and message router");
                System.err.println("       https://openstatic.org/routeput/");
                System.err.println("");
            }
            
            if (cmd.hasOption("?"))
            {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp( "routeput", options );
                System.exit(0);
            }

            if (cmd.hasOption("u"))
            {
                String[] upstreams = cmd.getOptionValues('u');
                for(int i = 0; i < upstreams.length; i++)
                {
                    String upstreamValue = upstreams[i];
                    if (upstreamValue.contains("@"))
                    {
                        String[] parts = upstreamValue.split("@", 2);
                        channel = RoutePutChannel.getChannel(parts[0]);
                        upstreamValue = parts[1];
                    }
                    RoutePutChannel.connectUpstream(channel, upstreamValue);
                }
            }
            
            if (cmd.hasOption("c"))
            {
                File config = new File(cmd.getOptionValue('c',"routeput.json"));
                settings = RoutePutServer.loadJSONObject(config);
                serverMode = true;
            }

            if (cmd.hasOption("p"))
            {
                int port = Integer.valueOf(cmd.getOptionValue('p',"6144")).intValue();
                settings.put("port", port);
            }

            String inputChannel = null;
            String outputChannel = null;

            if (cmd.hasOption("o"))
            {
                outputChannel = cmd.getOptionValue('o');
            }
            
            if (cmd.hasOption("i"))
            {
                inputChannel = cmd.getOptionValue('i');
            }

            if (cmd.hasOption("x"))
            {
                inputChannel = cmd.getOptionValue('x');
                outputChannel = inputChannel;
            }

            if (inputChannel != null)
            {
                final RoutePutOutputStream routeputOutputStream = new RoutePutOutputStream(RoutePutChannel.getChannel(inputChannel));
                Thread inputCopier = new Thread(() -> {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = System.in.read(buf)) != -1) {
                            routeputOutputStream.write(buf, 0, n);
                        }
                        routeputOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                inputCopier.start();
            }
            
            if (outputChannel != null)
            {
                final RoutePutInputStream routeputInputStream = new RoutePutInputStream();
                Thread outputCopier = new Thread(() -> {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = routeputInputStream.read(buf)) != -1) {
                            System.out.write(buf, 0, n);
                        }
                        routeputInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                outputCopier.start();
                RoutePutChannel.getChannel(outputChannel).addMessageListener(routeputInputStream);
            }

            if (serverMode)
            {
                RoutePutServer rps = new RoutePutServer(settings);
                rps.setState(true);
            }

            while(RoutePutMain.keep_running)
            {
                try
                {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        
    }

    public static void binaryTx(String url, RoutePutChannel channel)
    {
        RoutePutClient rpc = new RoutePutClient(channel, url);
        RoutePutOutputStream rpos = new RoutePutOutputStream(rpc.getDefaultChannel());
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
                    Thread.sleep(1);
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

    // TEST overwriting of properties and cascading merges
    public static void propertyClientTest(String url, RoutePutChannel channel)
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
        rpc.connect();
        //rpc.becomeCollector();
        RandomQuotes quotes = new RandomQuotes();
        try
        {
            while(rpc.isConnected())
            {
                long ts = System.currentTimeMillis();
                String tsString = String.valueOf(ts);
                String strA = tsString.substring(tsString.length() - 2);
                String strB = tsString.substring(tsString.length() -4, tsString.length() -2);
                RoutePutMessage rpm = new RoutePutMessage();
                JSONObject setChannelProperty = new JSONObject();
                JSONObject subblocks = new JSONObject();
                subblocks.put(strA, strB);
                setChannelProperty.put("block", subblocks);
                setChannelProperty.put("lastTS", tsString);
                setChannelProperty.put("ts", ts);
                rpm.setMetaField("setChannelProperty", setChannelProperty);
                rpm.setChannel(channel);
                rpc.send(rpm);

                System.err.println("SENDING: " + rpm.toString());
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
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
        try
        {
             rpc.connectAndWait(15000);
            //rpc.becomeCollector();
            RandomQuotes quotes = new RandomQuotes();
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
        try
        {
            rpc.connectAndWait(15000); // Connect and wait for connection to be established
            //rpc.becomeCollector();
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