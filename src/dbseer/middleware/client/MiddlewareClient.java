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
import dbseer.middleware.packet.MiddlewarePacketDecoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Dong Young Yoon on 12/1/15.
 *
 * The client for the middleware.
 */
public class MiddlewareClient implements Runnable
{
	private static final int MAX_RETRY = 3;

	private String host;
	private int port;
	private int retry;

	private String sysLogPath;
	private String dbLogPath;

	private Channel channel = null;
	private ExecutorService requesterExecutor = null;
	private MiddlewareClientLogRequester logRequester = null;

	private ExecutorService heartbeatSenderExecutor = null;
	private MiddlewareClientHeartbeatSender heartbeatSender = null;

	public MiddlewareClient(String host, int port, String sysLogPath, String dbLogPath)
	{
		this.retry = 0;
		this.host = host;
		this.port = port;
		this.sysLogPath = sysLogPath;
		this.dbLogPath = dbLogPath;
	}

	public void setDebug()
	{
		Log.set(Log.LEVEL_DEBUG);
	}

	public void run()
	{
		// debug info
		Log.debug(String.format("host = %s", host));
		Log.debug(String.format("port = %d", port));
		Log.debug(String.format("db log = %s", dbLogPath));
		Log.debug(String.format("sys log = %s", sysLogPath));

		// client needs to handle incoming messages from the middleware as well.
		EventLoopGroup group = new NioEventLoopGroup(4);

		try
		{
			// set up log files
			File dbLogFile = new File(dbLogPath);
			File sysLogFile = new File(sysLogPath);
			final PrintWriter dbLogWriter = new PrintWriter(new FileWriter(dbLogFile, false));
			final PrintWriter sysLogWriter = new PrintWriter(new FileWriter(sysLogFile, false));

			final MiddlewareClient client = this;

			Bootstrap b = new Bootstrap();
			b.group(group)
					.channel(NioSocketChannel.class)
					.handler(new ChannelInitializer<SocketChannel>()
					{
						@Override
						protected void initChannel(SocketChannel ch) throws Exception
						{
							ChannelPipeline p = ch.pipeline();
							p.addLast(new IdleStateHandler(10, 0, 0));
							p.addLast(new MiddlewarePacketDecoder(),new MiddlewareClientHandler(client, sysLogWriter, dbLogWriter));
						}
					});

			ChannelFuture f = b.connect(host, port).sync();
			channel = f.channel();
			Log.debug("Connected to the middleware.");

			channel.closeFuture().sync();
		}
		catch (Exception e)
		{
			Log.error(e.getMessage());
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

	public MiddlewareClientLogRequester getLogRequester()
	{
		return logRequester;
	}

	public void startMonitoring() throws Exception
	{
		if (retry >= MAX_RETRY)
		{
			throw new Exception(String.format("Middleware failed to start with %d retries", MAX_RETRY));
		}
		ByteBuf b = Unpooled.buffer();
		b.writeInt(MiddlewareConstants.PACKET_START_MONITORING);
		b.writeInt(0);
		channel.writeAndFlush(b);
		Log.debug("Start monitoring packet sent.");
		retry++;
	}

	public void stopMonitoring() throws Exception
	{
		this.stopExecutors();
		ByteBuf b = Unpooled.buffer();
		b.writeInt(MiddlewareConstants.PACKET_STOP_MONITORING);
		b.writeInt(0);
		channel.writeAndFlush(b);
		Log.debug("Stop monitoring packet sent.");
	}

	public void startRequester() throws Exception
	{
		logRequester = new MiddlewareClientLogRequester(channel);
		requesterExecutor = Executors.newSingleThreadExecutor();
		requesterExecutor.submit(logRequester);
		Log.debug("Log requester launched.");
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
			requesterExecutor.shutdown();
		}
		if (heartbeatSenderExecutor != null)
		{
			heartbeatSenderExecutor.shutdown();
		}
	}

}
