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

package dbseer.middleware.server;

import com.esotericsoftware.minlog.Log;
import dbseer.middleware.constant.MiddlewareConstants;
import dbseer.middleware.data.Server;
import dbseer.middleware.log.LogTailer;
import dbseer.middleware.log.LogTailerListener;
import dbseer.middleware.packet.MiddlewarePacketDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.ini4j.Ini;

import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Dong Young Yoon on 11/27/15.
 *
 * The server class for the middleware.
 */
public class MiddlewareServer
{
	private String id;
	private String password;
	private int port;
	private String dbLogPath;
	private String sysLogPath;
	private String namedPipePath;
	private boolean monitoring;

	private Map<String, Server> servers;

	private File dbLogFile = null;
	private RandomAccessFile namedPipeFile = null;
	private ExecutorService tailerExecutor = null;
	private LogTailer dbLogTailer = null;

	private ArrayBlockingQueue<String> dbLogQueue;

	private ChannelGroup connectedChannelGroup;

	public MiddlewareServer(String id, String password, int port, String dbLogPath, String sysLogPath, String namedPipePath, Map<String, Server> servers)
	{
		this.id = id;
		this.password = password;
		this.port = port;
		this.dbLogPath = dbLogPath;
		this.sysLogPath = sysLogPath;
		this.namedPipePath = namedPipePath;
		this.servers = servers;
		this.monitoring = false;
		this.connectedChannelGroup = new DefaultChannelGroup("all-connected", GlobalEventExecutor.INSTANCE);
	}

	public ChannelGroup getConnectedChannelGroup()
	{
		return connectedChannelGroup;
	}

	public void run() throws Exception
	{
		// basic log info.
		Log.info(String.format("Listening port = %d", port));
		Log.info(String.format("DB log dir = %s", dbLogPath));
		Log.info(String.format("System log dir = %s", sysLogPath));

		// print server info.
		for (Server s : servers.values())
		{
			s.printLogInfo();
			// test MySQL/MariaDB connection using JDBC before we start anything.
			if (!s.testConnection())
			{
				throw new Exception("Unable to connect to the MySQL server with the given credential.");
			}
		}

		// open named pipe.
		File checkPipeFile = new File(this.namedPipePath);
		if (checkPipeFile == null || !checkPipeFile.exists() || checkPipeFile.isDirectory())
		{
			throw new Exception("Cannot open the named pipe for communication with dbseerroute. " +
					"You must run Maxscale with dbseerroute with correct named pipe first.");
		}

		namedPipeFile = new RandomAccessFile(this.namedPipePath, "rwd");
		if (namedPipeFile == null)
		{
			throw new Exception("Cannot open the named pipe for communication with dbseerroute. " +
					"You must run Maxscale with dbseerroute with correct named pipe first.");
		}

		// attach shutdown hook.
		MiddlewareServerShutdown shutdownThread = new MiddlewareServerShutdown(this);
		Runtime.getRuntime().addShutdownHook(shutdownThread);

		// let's start accepting connections.
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workerGroup = new NioEventLoopGroup(4);

		final MiddlewareServer server = this;
		try
		{
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 128)
					.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.handler(new MiddlewareServerConnectionHandler(server))
					.childHandler(new ChannelInitializer<SocketChannel>()
					{
						@Override
						protected void initChannel(SocketChannel ch) throws Exception
						{
							ChannelPipeline p = ch.pipeline();
							p.addLast(new IdleStateHandler(10, 0, 0));
							p.addLast(new MiddlewarePacketDecoder(), new MiddlewareServerHandler(server));
						}
					});


			Log.info("Middleware is now accepting connections.");

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
			tailerExecutor.shutdown();
		}
	}

	public boolean startMonitoring()
	{
		// initialize queues
		dbLogQueue = new ArrayBlockingQueue<String>(MiddlewareConstants.QUEUE_SIZE);

		try
		{
			// start logging at MaxScale dbseerroute.
			namedPipeFile.writeBytes("1");

			// start monitoring process for each server.
			for (Server s : servers.values())
			{
				s.startMonitoring();
			}

			// start tailer to collect transaction log data.
			runTailer();
		}
		catch (Exception e)
		{
			Log.error("Exception while starting monitoring: " + e.getMessage());
			return false;
		}

		monitoring = true;
		return true;
	}

	public boolean stopMonitoring()
	{
		try
		{
			// stop logging at MaxScale dbseerroute.
			namedPipeFile.writeBytes("0");

			// stop monitoring process for each server.
			for (Server s : servers.values())
			{
				s.stopMonitoring();
				s.getLogQueue().clear();
			}
			if (dbLogQueue != null)
			{
				dbLogQueue.clear();
			}

			// stop transaction log tailers.
			if (tailerExecutor != null)
			{
				tailerExecutor.shutdown();
			}
		}
		catch (Exception e)
		{
			Log.error("Exception while stopping monitoring: " + e.getMessage());
			e.printStackTrace();
			return false;
		}

		monitoring = false;
		return true;
	}

	public boolean isMonitoring()
	{
		return monitoring;
	}

	private void runTailer() throws Exception
	{
		if (tailerExecutor != null)
		{
			tailerExecutor.shutdownNow();
		}

		dbLogFile = new File(dbLogPath);

		// discard first line for db log because of a possible truncates.
		LogTailerListener dbLogListener = new LogTailerListener(dbLogQueue, true);

		// starts from the last line for db log.
		dbLogTailer = new LogTailer(dbLogFile, dbLogListener, 250, -1);

		tailerExecutor = Executors.newFixedThreadPool(1);
		tailerExecutor.submit(dbLogTailer);
	}

	public ArrayBlockingQueue<String> getDbLogQueue()
	{
		return dbLogQueue;
	}

	public Server getServer(String name)
	{
		return servers.get(name);
	}

	public String getServerList()
	{
		String serverStr = "";
		Collection<Server> serverList = servers.values();
		for (Server s : serverList)
		{
			serverStr += s.getName();
			serverStr += ",";
		}

		return serverStr;
	}

	public static void main(String[] args)
	{
		// set up logger
		Log.set(Log.LEVEL_INFO); // DEBUG for now.

		String configPath = "./middleware.cnf";
		// handle command-line options with commons CLI
		CommandLineParser clParser = new DefaultParser();
		Options options = new Options();

		Option configOption = Option.builder("c")
				.hasArg()
				.argName("FILE")
				.required(false)
				.desc("use this configuration file (DEFAULT: middleware.cnf)")
				.build();

		Option helpOption = Option.builder("h")
				.longOpt("help")
				.required(false)
				.desc("print this message")
				.build();

		Option debugOption = Option.builder("d")
				.longOpt("debug")
				.required(false)
				.desc("print debug messages")
				.build();

		options.addOption(configOption);
		options.addOption(helpOption);
		options.addOption(debugOption);

		HelpFormatter formatter = new HelpFormatter();

		try
		{
			CommandLine line = clParser.parse(options, args);
			int port = 3555; // default port
			String dbLogPath, sysLogPath, dbUser, dbPassword, dbHost, dbPort, sshUser, monitorDir;

			if (line.hasOption("h"))
			{
				formatter.printHelp("MiddlewareServer", options, true);
				return;
			}
			if (line.hasOption("c"))
			{
				configPath = line.getOptionValue("c");
			}
			if (line.hasOption("d"))
			{
				Log.set(Log.LEVEL_DEBUG);
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
			String id = section.get("id");
			if (id == null)
			{
				throw new Exception("'id' is missing in the configuration file.");
			}
			String password = section.get("password");
			if (password == null)
			{
				throw new Exception("'password' is missing in the configuration file.");
			}
			String namedPipePath = section.get("named_pipe");
			if (namedPipePath == null)
			{
				throw new Exception("'named_pipe' is missing in the configuration file.");
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
			File dbLogFile = new File(dbLogPath);

			// error if dblog_path is a directory.
			if (dbLogFile.exists() && dbLogFile.isDirectory())
			{
				throw new Exception(String.format("dblog_path: '%s' is a directory, not a file.", dbLogPath));
			}
			sysLogPath = section.get("syslog_dir");
			if (sysLogPath == null)
			{
				throw new Exception("'syslog_dir' is missing in the configuration file.");
			}

			File sysLogDir = new File(sysLogPath);
			// check syslog_dir exists.
			if (sysLogDir.exists() && sysLogDir.isFile())
			{
				throw new Exception(String.format("syslog_dir: '%s' is a file, not a directory.", sysLogDir));
			}
			// if it doesn't exist, create the directory.
			else if (!sysLogDir.exists())
			{
				sysLogDir.mkdirs();
			}
			String serverStr = section.get("servers");
			if (serverStr == null)
			{
				throw new Exception("'servers' is missing in the configuration file.");
			}
			String[] servers = serverStr.split(MiddlewareConstants.SERVER_STRING_DELIMITER);
			Set<String> checkDuplicateServerSet = new HashSet<>();
			for (String s : servers)
			{
				if (!checkDuplicateServerSet.add(s))
				{
					throw new Exception("There are duplicate server names.");
				}
			}

			HashMap<String, Server> serverMap = new HashMap<>();

			for (String server : servers)
			{
				Ini.Section serverSection = ini.get(server);
				if (serverSection == null)
				{
					throw new Exception(String.format("'%s' section is missing in the configuration file.", server));
				}

				Server s = readServerConfig(server, serverSection, sysLogPath);
				serverMap.put(server, s);
			}

			MiddlewareServer server = new MiddlewareServer(id, password, port, dbLogPath, sysLogPath, namedPipePath, serverMap);
			server.run();
		}
		catch (ParseException e)
		{
			//System.out.println("USAGE: MiddlewareServer -d <dblogfile> -s <syslogfile>");
			formatter.printHelp("MiddlewareServer", options, true);
			Log.error(e.getMessage());
		}
		catch (Exception e)
		{
			Log.error(e.getMessage());
		}
	}

	private static Server readServerConfig(String name, Ini.Section section, String logPath) throws Exception
	{
		String dbUser, dbPassword, dbHost, dbPort, sshUser, monitorDir, monitorScript;

		dbHost = section.get("db_host");
		if (dbHost == null)
		{
			throw new Exception("'db_host' is missing in the configuration file.");
		}
		dbPort = section.get("db_port");
		if (dbPort == null)
		{
			throw new Exception("'db_port' is missing in the configuration file.");
		}
		if (!StringUtils.isNumeric(dbPort))
		{
			throw new Exception("'db_port' must be a number.");
		}
		dbUser = section.get("db_user");
		if (dbUser == null)
		{
			throw new Exception("'db_user' is missing in the configuration file.");
		}
		dbPassword = section.get("db_pw");
		if (dbPassword == null)
		{
			throw new Exception("'db_pw' is missing in the configuration file.");
		}
		sshUser = section.get("ssh_user");
		if (sshUser == null)
		{
			throw new Exception("'ssh_user' is missing in the configuration file.");
		}
		monitorDir = section.get("monitor_dir");
		if (monitorDir == null)
		{
			throw new Exception("'monitor_dir' is missing in the configuration file.");
		}
		monitorScript = section.get("monitor_script");
		if (monitorScript == null)
		{
			throw new Exception("'monitor_script' is missing in the configuration file.");
		}

		return new Server(name, dbHost, dbPort, dbUser, dbPassword, sshUser, monitorDir, monitorScript, logPath);
	}

	public String getId()
	{
		return id;
	}

	public String getPassword()
	{
		return password;
	}

	public RandomAccessFile getNamedPipeFile()
	{
		return namedPipeFile;
	}
}
