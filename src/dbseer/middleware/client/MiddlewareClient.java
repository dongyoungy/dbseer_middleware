/*
 * Copyright 2013 Barzan Mozafari
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dbseer.middleware.client;

import com.esotericsoftware.minlog.Log;
import dbseer.middleware.constant.MiddlewareConstants;
import dbseer.middleware.event.MiddlewareClientEvent;
import dbseer.middleware.packet.MiddlewarePacketDecoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Dong Young Yoon on 12/1/15.
 *
 * The client for the middleware.
 */
public class MiddlewareClient extends Observable implements Runnable
{
	private static final int MAX_RETRY = 3;

	private String id;
	private String password;
	private String host;
	private int port;
	private int retry;
	private boolean isMonitoring;

	private String logPath;

	private Channel channel = null;
	private ExecutorService requesterExecutor = null;

	private ExecutorService heartbeatSenderExecutor = null;
	private MiddlewareClientHeartbeatSender heartbeatSender = null;

	private MiddlewareClientLogRequester txLogRequester = null;
	private Map<String, MiddlewareClientLogRequester> sysLogRequester = null;

	private ArrayList<String> serverNameList = null;

	public MiddlewareClient(String host, String id, String password, int port, String logPath)
	{
		this.retry = 0;
		this.id = id;
		this.password = password;
		this.host = host;
		this.port = port;
		this.logPath = logPath;
		this.isMonitoring = false;
		this.sysLogRequester = new HashMap<>();
		this.serverNameList = new ArrayList<>();
	}

	public void setLogLevel(int level)
	{
		Log.set(level);
	}

	public void run()
	{
		// debug info
		Log.debug(String.format("host = %s", host));
		Log.debug(String.format("port = %d", port));
		Log.debug(String.format("log path = %s", logPath));

		// client needs to handle incoming messages from the middleware as well.
		EventLoopGroup group = new NioEventLoopGroup(4);

		try
		{
			File logDir = new File(logPath);
			if (!logDir.exists())
			{
				logDir.mkdirs();
			}

			final MiddlewareClient client = this;

			Bootstrap b = new Bootstrap();
			b.group(group)
					.channel(NioSocketChannel.class)
					.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.handler(new ChannelInitializer<SocketChannel>()
					{
						@Override
						protected void initChannel(SocketChannel ch) throws Exception
						{
							ChannelPipeline p = ch.pipeline();
							p.addLast(new IdleStateHandler(10, 0, 0));
							p.addLast(new MiddlewarePacketDecoder(),new MiddlewareClientHandler(client));
						}
					});

			ChannelFuture f = b.connect(host, port).sync();
			channel = f.channel();
			Log.debug("Connected to the middleware.");

			ByteBuf buf = Unpooled.buffer();
			buf.writeInt(MiddlewareConstants.PACKET_CHECK_VERSION);
			buf.writeInt(MiddlewareConstants.PROTOCOL_VERSION.getBytes("UTF-8").length);
			buf.writeBytes(MiddlewareConstants.PROTOCOL_VERSION.getBytes("UTF-8"));
			channel.writeAndFlush(buf);

			channel.closeFuture().sync();
		}
		catch (Exception e)
		{
			if (e instanceof InterruptedException)
			{

			}
			else
			{
				setChanged();
				notifyObservers(new MiddlewareClientEvent(MiddlewareClientEvent.ERROR, e));
			}
			Log.error(e.getMessage());
			e.printStackTrace();
		}
		finally
		{
			group.shutdownGracefully();
			this.stopExecutors();
		}
	}

	public Channel getChannel()
	{
		return channel;
	}

	public MiddlewareClientLogRequester getTxLogRequester()
	{
		return txLogRequester;
	}

	public MiddlewareClientLogRequester getSysLogRequester(String server)
	{
		return sysLogRequester.get(server);
	}

	public void startMonitoring() throws Exception
	{
		if (retry >= MAX_RETRY)
		{
			throw new Exception(String.format("Middleware failed to start with %d retries", MAX_RETRY));
		}

		// clear server names.
		this.serverNameList.clear();

		if (channel != null)
		{
			String idPassword = this.id + "@" + this.password;
			ByteBuf b = Unpooled.buffer();
			b.writeInt(MiddlewareConstants.PACKET_START_MONITORING);
			b.writeInt(idPassword.getBytes("UTF-8").length);
			b.writeBytes(idPassword.getBytes("UTF-8"));
			channel.writeAndFlush(b);
		}
		Log.debug("Start monitoring packet sent.");
		retry++;
	}

	public void stopMonitoring() throws Exception
	{
		this.stopExecutors();
		if (channel != null)
		{
			ByteBuf b = Unpooled.buffer();
			b.writeInt(MiddlewareConstants.PACKET_STOP_MONITORING);
			b.writeInt(0);
			channel.writeAndFlush(b);
		}
		Log.debug("Stop monitoring packet sent.");

		// reset retry count.
		retry = 0;
		isMonitoring = false;
	}

	public void requestServerList() throws Exception
	{
		if (channel != null)
		{
			ByteBuf b= Unpooled.buffer();
			b.writeInt(MiddlewareConstants.PACKET_REQUEST_SERVER_LIST);
			b.writeInt(0);
			channel.writeAndFlush(b);
		}
		Log.debug("Server list request packet sent.");
	}

	public PrintWriter startTxLogRequester() throws Exception
	{
		if (requesterExecutor == null)
		{
			requesterExecutor = Executors.newCachedThreadPool();
		}
		txLogRequester =
				new MiddlewareClientLogRequester(channel, MiddlewareConstants.PACKET_REQUEST_TX_LOG);

		requesterExecutor.submit(txLogRequester);

		File dbLogFile = new File(logPath + File.separator + MiddlewareConstants.TX_LOG_PREFIX);
		PrintWriter writer = new PrintWriter(new FileWriter(dbLogFile, false));

		Log.debug("Tx Log requester launched.");

		return writer;
	}

	public Map<String, PrintWriter> startSysLogRequester(String serverStr) throws Exception
	{
		if (requesterExecutor == null)
		{
			requesterExecutor = Executors.newCachedThreadPool();
		}

		Map<String, PrintWriter> writers = new HashMap<>();
		String[] servers = serverStr.split(MiddlewareConstants.SERVER_STRING_DELIMITER);
		for (String server : servers)
		{
			MiddlewareClientLogRequester logRequester =
					new MiddlewareClientLogRequester(channel, MiddlewareConstants.PACKET_REQUEST_SYS_LOG, server);

			requesterExecutor.submit(logRequester);
			sysLogRequester.put(server, logRequester);

			File sysLogFile = new File(logPath + File.separator + MiddlewareConstants.SYS_LOG_PREFIX + "." + server);
			PrintWriter writer = new PrintWriter(new FileWriter(sysLogFile, false));
			writers.put(server, writer);
			serverNameList.add(server);
		}

		Log.debug("Sys Log requesters launched.");

		return writers;
	}

	public void startHeartbeatSender() throws Exception
	{
		heartbeatSender = new MiddlewareClientHeartbeatSender(channel);
		heartbeatSenderExecutor = Executors.newSingleThreadExecutor();
		heartbeatSenderExecutor.submit(heartbeatSender);
		Log.debug("heartbeat sender launched.");
	}

	public void stopExecutors()
	{
		if (requesterExecutor != null)
		{
			requesterExecutor.shutdownNow();
		}
		if (heartbeatSenderExecutor != null)
		{
			heartbeatSenderExecutor.shutdownNow();
		}
		txLogRequester = null;
		sysLogRequester = new HashMap<>();

		// clear server names.
		this.serverNameList.clear();
	}

	public void setMonitoring(boolean monitoring)
	{
		isMonitoring = monitoring;
		setChanged();
		MiddlewareClientEvent event;
		if (isMonitoring)
		{
			event = new MiddlewareClientEvent(MiddlewareClientEvent.IS_MONITORING);
		}
		else
		{
			event = new MiddlewareClientEvent(MiddlewareClientEvent.IS_NOT_MONITORING);
		}
		notifyObservers(event);
	}

	public void setMonitoring(boolean monitoring, String serverStr)
	{
		isMonitoring = monitoring;
		setChanged();
		MiddlewareClientEvent event;
		if (isMonitoring)
		{
			event = new MiddlewareClientEvent(MiddlewareClientEvent.IS_MONITORING);
		}
		else
		{
			event = new MiddlewareClientEvent(MiddlewareClientEvent.IS_NOT_MONITORING);
		}
		event.serverStr = serverStr;
		notifyObservers(event);
	}

	public boolean isMonitoring()
	{
		return isMonitoring;
	}

	public void disconnect() throws Exception
	{
		if (channel != null && channel.isActive())
		{
			channel.close().sync();
		}
	}

	public ArrayList<String> getServerNameList()
	{
		return serverNameList;
	}
}
