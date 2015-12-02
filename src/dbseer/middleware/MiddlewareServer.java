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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.commons.cli.*;
import org.ini4j.Ini;

import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;

/**
 * Created by Dong Young Yoon on 11/27/15.
 *
 * The server class for the middleware.
 */
public class MiddlewareServer
{
	private int port;
	private String dbLogPath;
	private String sysLogPath;
	private String dbId;
	private String dbPassword;

	private boolean hasConnected;
	private String remoteHostString;

	public MiddlewareServer(int port, String dbLogPath, String sysLogPath, String dbId, String dbPassword)
	{
		this.port = port;
		this.dbLogPath = dbLogPath;
		this.sysLogPath = sysLogPath;
		this.dbId = dbId;
		this.dbPassword = dbPassword;
		this.hasConnected = false;
		this.remoteHostString = "";
	}

	public void run() throws Exception
	{
		// set logger
		Log.info(String.format("Listening port = %d", port));
		Log.info(String.format("DB log path = %s", dbLogPath));
		Log.info(String.format("System log path = %s", sysLogPath));
		Log.info(String.format("DB ID = %s", dbId));
		Log.info(String.format("DB PW = %s", dbPassword));

		// Use tailer to collect log data.



		// let's start accepting connections.
		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup();

		try
		{
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 128)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.handler(new MiddlewareServerConnectionHandler(this))
					.childHandler(new ChannelInitializer<SocketChannel>()
					{
						@Override
						protected void initChannel(SocketChannel ch) throws Exception
						{
							ChannelPipeline p = ch.pipeline();
							p.addLast(new MiddlewareServerHandler());
						}
					});

			// bind and start accepting connections.
			ChannelFuture cf = b.bind(port).sync();

			// shutdown the server.
			cf.channel().closeFuture().sync();
		}
		catch (Exception e)
		{
			Log.error(e.getMessage());
		}
		finally
		{
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}

	/*
	 * Store the remote address. Middleware only allows a single connection.
	 */
	public void setRemote(InetSocketAddress remoteAddress)
	{
		if (hasConnected)
		{
			return;
		}
		remoteHostString = remoteAddress.getHostString();
		hasConnected = true;
	}

	public static void main(String[] args)
	{
		// set up logger
		Log.set(Log.LEVEL_DEBUG);

		String configPath = "./middleware.cnf";
		// handle command-line options with commons CLI
		CommandLineParser clParser = new DefaultParser();
		Options options = new Options();

		Option configOption = OptionBuilder.withArgName("config")
				.hasArg()
				.withArgName("FILE")
				.isRequired(false)
				.withDescription("use this configuration file (DEFAULT: middleware.cnf)").create("c");

		Option helpOption = OptionBuilder.withArgName("help")
				.withLongOpt("help")
				.isRequired(false)
				.withDescription("print this message")
				.create("h");

		options.addOption(configOption);
		options.addOption(helpOption);

		HelpFormatter formatter = new HelpFormatter();

		try
		{
			CommandLine line = clParser.parse(options, args);
			int port = 3555; // default port
			String dbLogPath, sysLogPath, dbId, dbPassword;

			if (line.hasOption("c"))
			{
				configPath = line.getOptionValue("c");
			}

			// get configuration
			Ini ini = new Ini();
			File configFile = new File(configPath);
			if (!configFile.exists() || configFile.isDirectory())
			{
				throw new Exception("configuration file (" + configFile.getCanonicalPath() + ") does not exist or is a directory.");
			}

			ini.load(new FileReader(configFile));
			Ini.Section section = ini.get("dbseer_middleware");
			if (section == null)
			{
				throw new Exception("'dbseer_middleware' section cannot be found in the configuration file.");
			}
			String portStr = section.get("listen_port");
			if (portStr != null)
			{
				port = Integer.parseInt(portStr);
			}
			dbLogPath = section.get("dblog_path");
			if (dbLogPath == null)
			{
				throw new Exception("'dblog_path' is missing in the configuration file.");
			}
			sysLogPath = section.get("syslog_path");
			if (sysLogPath == null)
			{
				throw new Exception("'syslog_path' is missing in the configuration file.");
			}
			dbId = section.get("db_id");
			if (dbId == null)
			{
				throw new Exception("'db_id' is missing in the configuration file.");
			}
			dbPassword = section.get("db_pw");
			if (dbPassword == null)
			{
				throw new Exception("'db_pw' is missing in the configuration file.");
			}

			MiddlewareServer server = new MiddlewareServer(port, dbLogPath, sysLogPath, dbId, dbPassword);
			server.run();
		}
		catch (ParseException e)
		{
			//System.out.println("USAGE: MiddlewareServer -d <dblogfile> -s <syslogfile>");
			formatter.printHelp("MiddlewareServer", options, true);
			System.out.println("ERROR: " + e.getMessage());
		}
		catch (Exception e)
		{
			System.out.println("ERROR: " + e.getMessage());
			e.printStackTrace();
		}
	}
}

