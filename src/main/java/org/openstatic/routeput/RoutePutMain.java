package org.openstatic.routeput;

import java.io.File;
import org.openstatic.routeput.client.*;
import org.apache.commons.cli.*;
import org.json.*;

public class RoutePutMain
{
    
    public static void main(String[] args)
    {
        Thread channelTracker = RoutePutChannel.initTracker();
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
            options.addOption(new Option("x", "client", true, "Quote Bot Client Mode"));
            options.addOption(new Option("y", "clienty", true, "Test Client Mode"));
            options.addOption(new Option("m", "message", true, "Set Message for test client"));

            Option upstreamOption = new Option("u", "upstream", true, "Connect to upstream server");
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
                if (channel == null) channel = RoutePutChannel.getChannel("lobby");
                clientTest(cmd.getOptionValue('x',"openstatic.org"), settings.optInt("port", 6144), channel);
                System.exit(0);
            }

            if (cmd.hasOption("y"))
            {
                if (channel == null) channel = RoutePutChannel.getChannel("LoRa");
                clientTest2(cmd.getOptionValue('y',"openstatic.org"), settings.optInt("port", 6144), channel, settings.optString("message", "Hello World!"));
                System.exit(0);
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

    public static void clientTest(String host, int port, RoutePutChannel channel)
    {
        RoutePutClient rpc = new RoutePutClient(channel, "ws://" + host + ":" + String.valueOf(port) + "/channel/");
        rpc.addSessionListener(new RoutePutSessionListener(){
        
            @Override
            public void onConnect(RoutePutSession session, boolean local) {
                if (!local)
                {
                    System.err.println("Remote Session Connected: " + session.getConnectionId());
                    session.addMessageListener(new RoutePutMessageListener(){
                    
                        @Override
                        public void onMessage(RoutePutMessage message) {
                            System.err.println(session.getConnectionId() + " Received " + message.toString());
                        }
                    });
                } else {
                    System.err.println("Local client connected");
                }
            }
            
            @Override
            public void onClose(RoutePutSession session, boolean local) {
                if (!local)
                {
                    System.err.println("Remote Session Disconnected: " + session.getConnectionId());
                } else {
                    System.err.println("Local client disconnected");
                }
            }
        });
        rpc.addMessageListener(new RoutePutMessageListener(){
                    
            @Override
            public void onMessage(RoutePutMessage message) {
                System.err.println("CLIENT Received " + message.toString());
            }
        });
        rpc.setProperty("details", "random quote bot for testing");
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
                System.err.println("Sending: " + msg.toString());
                rpc.send(msg);
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public static void clientTest2(String host, int port, RoutePutChannel channel, String message)
    {
        RoutePutClient rpc = new RoutePutClient(channel, "ws://" + host + ":" + String.valueOf(port) + "/channel/");
        rpc.addSessionListener(new RoutePutSessionListener(){
        
            @Override
            public void onConnect(RoutePutSession session, boolean local) {
                if (!local)
                {
                    System.err.println("Remote Session Connected: " + session.getConnectionId());
                    session.addMessageListener(new RoutePutMessageListener(){
                    
                        @Override
                        public void onMessage(RoutePutMessage message) {
                            System.err.println(session.getConnectionId() + " Received " + message.toString());
                        }
                    });
                } else {
                    System.err.println("Local client connected");
                }
            }
            
            @Override
            public void onClose(RoutePutSession session, boolean local) {
                if (!local)
                {
                    System.err.println("Remote Session Disconnected: " + session.getConnectionId());
                } else {
                    System.err.println("Local client disconnected");
                }
            }
        });
        rpc.addMessageListener(new RoutePutMessageListener(){
                    
            @Override
            public void onMessage(RoutePutMessage message) {
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