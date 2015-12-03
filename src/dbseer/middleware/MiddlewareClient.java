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

package dbseer.middleware;

import com.esotericsoftware.minlog.Log;
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
 * The client for the middleware. Created for testing purposes.
 */
public class MiddlewareClient
{
	private static final int MAX_RETRY = 3;

	private String host;
	private int port;
	private int retry;

	private String sysLogPath;
	private String dbLogPath;

	private Channel channel = null;
	private ExecutorService requesterExecutor = null;

	public MiddlewareClient(String host, int port, String sysLogPath, String dbLogPath)
	{
		this.retry = 0;
		this.host = host;
		this.port = port;
		this.sysLogPath = sysLogPath;
		this.dbLogPath = dbLogPath;
	}

	public void run() throws Exception
	{
		// debug info
		Log.debug(String.format("host = %s", host));
		Log.debug(String.format("port = %d", port));
		Log.debug(String.format("db log = %s", dbLogPath));
		Log.debug(String.format("sys log = %s", sysLogPath));

		// set up log files
		File dbLogFile = new File(dbLogPath);
		File sysLogFile = new File(sysLogPath);
		final PrintWriter dbLogWriter = new PrintWriter(new FileWriter(dbLogFile, false));
		final PrintWriter sysLogWriter = new PrintWriter(new FileWriter(sysLogFile, false));

		// client needs to handle incoming messages from the middleware as well.
		EventLoopGroup group = new NioEventLoopGroup();

		final MiddlewareClient client = this;

		try
		{
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
							p.addLast(new MiddlewareClientHandler(client, sysLogWriter, dbLogWriter));
						}
					});

			ChannelFuture f = b.connect(host, port).sync();
			channel = f.channel();
			Log.debug("Connected to the middleware.");

			startMonitoring();

			channel.closeFuture().sync();
		}
		finally
		{
			group.shutdownGracefully();
			if (requesterExecutor != null)
			{
				requesterExecutor.shutdownNow();
			}
		}
	}

	public void startMonitoring() throws Exception
	{
		if (retry > MAX_RETRY)
		{
			throw new Exception(String.format("Middleware failed to start with %d retries", MAX_RETRY));
		}
		ByteBuf b = Unpooled.buffer();
		b.writeInt(MiddlewareConstants.PACKET_START_MONITORING);
		channel.writeAndFlush(b);
		Log.debug("Start monitoring packet sent.");
		b.release();
		retry++;
	}

	public void startRequester() throws Exception
	{
		MiddlewareClientLogRequester requester = new MiddlewareClientLogRequester(channel);
		requesterExecutor = Executors.newSingleThreadExecutor();
		requesterExecutor.submit(requester);
		Log.debug("Log requester launched.");
	}

	public static void main(String[] args)
	{
		// set up logger
		Log.set(Log.LEVEL_DEBUG);

		// handle command-line options
		CommandLineParser clParser = new DefaultParser();
		Options options = new Options();

		Option hostOption = Option.builder("h")
				.hasArg()
				.argName("HOST")
				.required(true)
				.desc("middleware hostname")
				.build();

		Option portOption = Option.builder("p")
				.hasArg()
				.argName("PORT")
				.required(true)
				.desc("middleware port")
				.build();

		Option sysLogOption = Option.builder("s")
				.hasArg()
				.argName("FILE")
				.required(true)
				.desc("file to print system log")
				.build();

		Option dbLogOption = Option.builder("d")
				.hasArg()
				.argName("FILE")
				.required(true)
				.desc("file to print database log")
				.build();

		Option helpOption = Option.builder("?")
				.longOpt("help")
				.required(false)
				.desc("print this message")
				.build();

		options.addOption(hostOption);
		options.addOption(portOption);
		options.addOption(sysLogOption);
		options.addOption(dbLogOption);
		options.addOption(helpOption);

		HelpFormatter formatter = new HelpFormatter();
		try
		{
			CommandLine line = clParser.parse(options, args);
			if (line.hasOption("?"))
			{
				formatter.printHelp("MiddlewareClient", options, true);
				return;
			}

			int port;
			String host, sysLogPath, dbLogPath;

			port = Integer.parseInt(line.getOptionValue("p"));
			host = line.getOptionValue("h");
			sysLogPath = line.getOptionValue("s");
			dbLogPath = line.getOptionValue("d");

			MiddlewareClient client = new MiddlewareClient(host, port, sysLogPath, dbLogPath);
			client.run();
		}
		catch (ParseException e)
		{
			//System.out.println("USAGE: MiddlewareServer -d <dblogfile> -s <syslogfile>");
			formatter.printHelp("MiddlewareClient", options, true);
			Log.error(e.getMessage());
		}
		catch (Exception e)
		{
			Log.error(e.getMessage());
		}
	}
}
