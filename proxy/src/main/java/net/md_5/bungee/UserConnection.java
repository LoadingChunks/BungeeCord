package net.md_5.bungee;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Getter;
import lombok.Synchronized;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.packet.*;

public class UserConnection extends GenericConnection implements ProxiedPlayer
{

    public final Packet2Handshake handshake;
    public Queue<DefinedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    public List<byte[]> loginPackets = new ArrayList<>();
    @Getter
    private final PendingConnection pendingConnection;
    @Getter
    private ServerConnection server;
    private UpstreamBridge upBridge;
    private DownstreamBridge downBridge;
    // reconnect stuff
    private int clientEntityId;
    private int serverEntityId;
    private volatile boolean reconnecting;
    // ping stuff
    private int trackingPingId;
    private long pingTime;
    @Getter
    private int ping;
    // Permissions
    private final Collection<String> groups = new HashSet<>();
    private final Map<String, Boolean> permissions = new HashMap<>();
    private final Object permMutex = new Object();

    public UserConnection(Socket socket, PendingConnection pendingConnection, PacketInputStream in, OutputStream out, Packet2Handshake handshake, List<byte[]> loginPackets)
    {
        super(socket, in, out);
        this.handshake = handshake;
        this.pendingConnection = pendingConnection;
        name = handshake.username;
        displayName = handshake.username;
        this.loginPackets = loginPackets;
        Collection<String> g = ProxyServer.getInstance().getConfigurationAdapter().getGroups(name);
        for (String s : g)
        {
            addGroups(s);
        }
    }

    @Override
    public void setDisplayName(String name)
    {
        ProxyServer.getInstance().getTabListHandler().onDisconnect(this);
        displayName = name;
        ProxyServer.getInstance().getTabListHandler().onConnect(this);
    }

    @Override
    public void connect(ServerInfo target)
    {
        if (server == null)
        {
            // First join
            BungeeCord.getInstance().connections.put(name, this);
            ProxyServer.getInstance().getTabListHandler().onConnect(this);
        }

        ServerConnectEvent event = new ServerConnectEvent(this, target);
        BungeeCord.getInstance().getPluginManager().callEvent(event);
        target = event.getTarget(); // Update in case the event changed target

        ProxyServer.getInstance().getTabListHandler().onServerChange(this);
        try
        {
            reconnecting = true;

            if (server != null)
            {
                out.write(new Packet9Respawn((byte) 1, (byte) 0, (byte) 0, (short) 256, "DEFAULT").getPacket());
                out.write(new Packet9Respawn((byte) -1, (byte) 0, (byte) 0, (short) 256, "DEFAULT").getPacket());
            }

            ServerConnection newServer = ServerConnection.connect(this, target, handshake, false);
            if (server == null)
            {
                // Once again, first connection
                clientEntityId = newServer.loginPacket.entityId;
                serverEntityId = newServer.loginPacket.entityId;
                out.write(newServer.loginPacket.getPacket());
                out.write(BungeeCord.getInstance().registerChannels().getPacket());

                upBridge = new UpstreamBridge();
                upBridge.start();
            } else
            {
                try
                {
                    downBridge.interrupt();
                    downBridge.join();
                } catch (InterruptedException ie)
                {
                }

                server.disconnect("Quitting");
                server.getInfo().removePlayer(this);

                Packet1Login login = newServer.loginPacket;
                serverEntityId = login.entityId;
                out.write(new Packet9Respawn(login.dimension, login.difficulty, login.gameMode, (short) 256, login.levelType).getPacket());
            }

            // Reconnect process has finished, lets get the player moving again
            reconnecting = false;

            // Add to new
            target.addPlayer(this);

            // Start the bridges and move on
            server = newServer;
            downBridge = new DownstreamBridge();
            downBridge.start();
        } catch (KickException ex)
        {
            destroySelf(ex.getMessage());
        } catch (Exception ex)
        {
            ex.printStackTrace(); // TODO: Remove
            destroySelf("Could not connect to server - " + ex.getClass().getSimpleName());
        }
    }

    private void destroySelf(String reason)
    {
        ProxyServer.getInstance().getPlayers().remove(this);

        disconnect(reason);
        if (server != null)
        {
            server.getInfo().removePlayer(this);
            server.disconnect("Quitting");
            ProxyServer.getInstance().getReconnectHandler().setServer(this);
        }
    }

    @Override
    public void disconnect(String reason)
    {
        ProxyServer.getInstance().getTabListHandler().onDisconnect(this);
        super.disconnect(reason);
    }

    @Override
    public void sendMessage(String message)
    {
        packetQueue.add(new Packet3Chat(message));
    }

    @Override
    public void sendData(String channel, byte[] data)
    {
        server.packetQueue.add(new PacketFAPluginMessage(channel, data));
    }

    @Override
    public InetSocketAddress getAddress()
    {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override
    @Synchronized("permMutex")
    public Collection<String> getGroups()
    {
        return Collections.unmodifiableCollection(groups);
    }

    @Override
    @Synchronized("permMutex")
    public void addGroups(String... groups)
    {
        for (String group : groups)
        {
            this.groups.add(group);
            for (String permission : ProxyServer.getInstance().getConfigurationAdapter().getPermissions(group))
            {
                setPermission(permission, true);
            }
        }
    }

    @Override
    @Synchronized( "permMutex")
    public void removeGroups(String... groups)
    {
        for (String group : groups)
        {
            this.groups.remove(group);
            for (String permission : ProxyServer.getInstance().getConfigurationAdapter().getPermissions(group))
            {
                setPermission(permission, false);
            }
        }
    }

    @Override
    @Synchronized("permMutex")
    public boolean hasPermission(String permission)
    {
        Boolean val = permissions.get(permission);
        return (val == null) ? false : val;
    }

    @Override
    @Synchronized( "permMutex")
    public void setPermission(String permission, boolean value)
    {
        permissions.put(permission, value);
    }

    private class UpstreamBridge extends Thread
    {

        public UpstreamBridge()
        {
            super("Upstream Bridge - " + name);
        }

        @Override
        public void run()
        {
            while (!socket.isClosed())
            {
                try
                {
                    byte[] packet = in.readPacket();
                    boolean sendPacket = true;
                    int id = Util.getId(packet);

                    switch (id)
                    {
                        case 0x00:
                            if (trackingPingId == new Packet0KeepAlive(packet).id)
                            {
                                int newPing = (int) (System.currentTimeMillis() - pingTime);
                                ProxyServer.getInstance().getTabListHandler().onPingChange(UserConnection.this, newPing);
                                ping = newPing;
                            }
                            break;
                        case 0x03:
                            Packet3Chat chat = new Packet3Chat(packet);
                            if (chat.message.startsWith("/"))
                            {
                                sendPacket = !ProxyServer.getInstance().getPluginManager().dispatchCommand(UserConnection.this, chat.message.substring(1));
                            } else
                            {
                                ChatEvent chatEvent = new ChatEvent(UserConnection.this, server, chat.message);
                                ProxyServer.getInstance().getPluginManager().callEvent(chatEvent);
                                sendPacket = !chatEvent.isCancelled();
                            }
                            break;
                        case 0xFA:
                            // Call the onPluginMessage event
                            PacketFAPluginMessage message = new PacketFAPluginMessage(packet);

                            // Might matter in the future
                            if (message.tag.equals("BungeeCord"))
                            {
                                continue;
                            }

                            PluginMessageEvent event = new PluginMessageEvent(UserConnection.this, server, message.tag, message.data);
                            ProxyServer.getInstance().getPluginManager().callEvent(event);

                            if (event.isCancelled())
                            {
                                continue;
                            }
                            break;
                    }

                    while (!server.packetQueue.isEmpty())
                    {
                        DefinedPacket p = server.packetQueue.poll();
                        if (p != null)
                        {
                            server.out.write(p.getPacket());
                        }
                    }

                    EntityMap.rewrite(packet, clientEntityId, serverEntityId);
                    if (sendPacket && !server.socket.isClosed())
                    {
                        server.out.write(packet);
                    }
                } catch (IOException ex)
                {
                    destroySelf("Reached end of stream");
                } catch (Exception ex)
                {
                    destroySelf(Util.exception(ex));
                }
            }
        }
    }

    private class DownstreamBridge extends Thread
    {

        public DownstreamBridge()
        {
            super("Downstream Bridge - " + name);
        }

        @Override
        public void run()
        {
            try
            {
                outer:
                while (!reconnecting)
                {
                    byte[] packet = server.in.readPacket();
                    int id = Util.getId(packet);

                    switch (id)
                    {
                        case 0x00:
                            trackingPingId = new Packet0KeepAlive(packet).id;
                            pingTime = System.currentTimeMillis();
                            break;
                        case 0x03:
                            Packet3Chat chat = new Packet3Chat(packet);
                            ChatEvent chatEvent = new ChatEvent(server, UserConnection.this, chat.message);
                            ProxyServer.getInstance().getPluginManager().callEvent(chatEvent);

                            if (chatEvent.isCancelled())
                            {
                                continue;
                            }
                            break;
                        case 0xC9:
                            PacketC9PlayerListItem playerList = new PacketC9PlayerListItem(packet);
                            if (!ProxyServer.getInstance().getTabListHandler().onListUpdate(UserConnection.this, playerList.username, playerList.online, playerList.ping))
                            {
                                continue;
                            }
                            break;
                        case 0xFA:
                            // Call the onPluginMessage event
                            PacketFAPluginMessage message = new PacketFAPluginMessage(packet);
                            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message.data));
                            PluginMessageEvent event = new PluginMessageEvent(server, UserConnection.this, message.tag, message.data);
                            ProxyServer.getInstance().getPluginManager().callEvent(event);

                            if (event.isCancelled())
                            {
                                continue;
                            }

                            if (message.tag.equals("BungeeCord"))
                            {
                                switch (in.readUTF())
                                {
                                    case "Disconnect":
                                        break outer;
                                    case "Forward":
                                        String target = in.readUTF();
                                        String channel = in.readUTF();
                                        short len = in.readShort();
                                        byte[] data = new byte[len];
                                        in.readFully(data);

                                        if (target.equals("ALL"))
                                        {
                                            for (String s : BungeeCord.getInstance().getServers().keySet())
                                            {
                                                Server server = BungeeCord.getInstance().getServer(s);
                                                server.sendData(channel, data);
                                            }
                                        } else
                                        {
                                            Server server = BungeeCord.getInstance().getServer(target);
                                            server.sendData(channel, data);
                                        }

                                        break;
                                    case "Connect":
                                        ServerInfo server = BungeeCord.getInstance().config.getServers().get(in.readUTF());
                                        if (server != null)
                                        {
                                            connect(server);
                                            break outer;
                                        }
                                        break;
                                }
                            }
                    }

                    while (!packetQueue.isEmpty())
                    {
                        DefinedPacket p = packetQueue.poll();
                        if (p != null)
                        {
                            out.write(p.getPacket());
                        }
                    }

                    EntityMap.rewrite(packet, serverEntityId, clientEntityId);
                    out.write(packet);
                }
            } catch (Exception ex)
            {
                destroySelf(Util.exception(ex));
            }
        }
    }
}
