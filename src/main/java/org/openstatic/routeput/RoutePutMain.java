package org.openstatic.routeput;

import java.io.File;
import org.openstatic.routeput.client.*;
import org.apache.commons.cli.*;
import org.json.*;

public class RoutePutMain
{
    
    public static void main(String[] args)
    {
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
            options.addOption(new Option("x", "client", true, "Client Mode"));
            Option upstreamOption = new Option("u", "upstream", true, "Connect to upstream server");
            upstreamOption.setOptionalArg(true);
            options.addOption(upstreamOption);

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
            
            if (cmd.hasOption("p"))
            {
                int port = Integer.valueOf(cmd.getOptionValue('p',"6144")).intValue();
                settings.put("port", port);
            }

            if (cmd.hasOption("x"))
            {
                clientTest(cmd.getOptionValue('x',"openstatic.org"), settings.optInt("port", 6144));
                System.exit(0);
            }
            
            RoutePutServer rps = new RoutePutServer(settings);
            rps.setState(true);
            
            if (cmd.hasOption("u"))
            {
                rps.connectUpstream("lobby", cmd.getOptionValue('u',"wss://openstatic.org/channel/lobby/"));
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        
    }

    public static void clientTest(String host, int port)
    {
        RoutePutClient rpc = new RoutePutClient("lobby", "ws://" + host + ":" + String.valueOf(port) + "/channel/lobby/");
        rpc.addSessionListener(new RoutePutRemoteSessionListener(){
        
            @Override
            public void onConnect(RoutePutRemoteSession session) {
                System.err.println("Remote Session Connected: " + session.getConnectionId());
                session.addMessageListener(new RoutePutMessageListener(){
                
                    @Override
                    public void onMessage(RoutePutMessage message) {
                        System.err.println(session.getConnectionId() + " Received " + message.toString());
                    }
                });
            }
        
            @Override
            public void onClose(RoutePutRemoteSession session) {
                System.err.println("Remote Session Disconnected: " + session.getConnectionId());

            }
        });
        rpc.connect();
        //rpc.becomeCollector();
        RandomQuotes quotes = new RandomQuotes();
        try
        {
            while(true)
            {
                JSONObject msg = new JSONObject();
                msg.put("event","chat");
                msg.put("text", quotes.nextQuote());
                msg.put("username", "Ghost in the machine");
                System.err.println("Sending: " + msg.toString());
                rpc.send(msg);
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}