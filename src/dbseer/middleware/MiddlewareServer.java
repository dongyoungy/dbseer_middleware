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
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.ini4j.Ini;

import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
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
	private int port;
	private String dbLogPath;
	private String sysLogPath;
	private String dbUser;
	private String dbPassword;
	private String dbHost;
	private String dbPort;
	private String testDB;

	private boolean hasConnected;
	private String remoteHostString;

	private File dbLogFile = null;
	private File sysLogFile = null;
	private Process dstatProcess = null;
	private ExecutorService tailerExecutor = null;
	private LogTailer dbLogTailer = null;
	private LogTailer sysLogTailer = null;

	private ArrayBlockingQueue<String> dbLogQueue;
	private ArrayBlockingQueue<String> sysLogQueue;

	public MiddlewareServer(int port, String dbLogPath, String sysLogPath, String dbUser, String dbPassword, String dbHost, String dbPort, String testDB)
	{
		this.port = port;
		this.dbLogPath = dbLogPath;
		this.sysLogPath = sysLogPath;
		this.dbUser = dbUser;
		this.dbPassword = dbPassword;
		this.dbHost = dbHost;
		this.dbPort = dbPort;
		this.testDB = testDB;
	}

	public void run() throws Exception
	{
		// set logger
		Log.info(String.format("Listening port = %d", port));
		Log.info(String.format("DB log path = %s", dbLogPath));
		Log.info(String.format("System log path = %s", sysLogPath));
		Log.info(String.format("DB Host = %s", dbHost));
		Log.info(String.format("DB Port = %s", dbPort));
		Log.info(String.format("DB User = %s", dbUser));
		Log.info(String.format("DB PW = %s", dbPassword));
		Log.info(String.format("Test DB = %s", testDB));

		// test MySQL/MariaDB connection using JDBC before we start anything.
		if (!testMySQLConnection())
		{
			throw new Exception("Unable to connect to the MySQL server with the given credential.");
		}

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
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.handler(new MiddlewareServerConnectionHandler(server))
					.childHandler(new ChannelInitializer<SocketChannel>()
					{
						@Override
						protected void initChannel(SocketChannel ch) throws Exception
						{
							ChannelPipeline p = ch.pipeline();
							p.addLast(new IdleStateHandler(10, 0, 0));
							p.addLast(new MiddlewareServerHandler(server));
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
		sysLogQueue = new ArrayBlockingQueue<String>(MiddlewareConstants.QUEUE_SIZE);

		try
		{
			// start dstat.
			runDstat();

			// sleep for 1.5 sec
			Thread.sleep(1500);

			// start tailer to collect log data.
			runTailer();
		}
		catch (Exception e)
		{
			Log.error("Exception while starting monitoring: " + e.getMessage());
			return false;
		}
		return true;
	}

	public boolean stopMonitoring()
	{
		// stop dstat.
		dstatProcess.destroy();

		// stop tailers.
		tailerExecutor.shutdown();

		return true;
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

	private void runDstat() throws Exception
	{
		if (dstatProcess != null)
		{
			dstatProcess.destroy();
		}

		String[] cmd = {"/bin/bash", "./rs-sysmon2/monitor.sh"};
		ProcessBuilder pb = new ProcessBuilder(cmd);
		File log = new File("dstat_log");

		// temp
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));

		Map<String, String> env = pb.environment();
		env.put("DSTAT_MYSQL_USER", dbUser);
		env.put("DSTAT_MYSQL_PWD", dbPassword);
		env.put("DSTAT_MYSQL_HOST", dbHost);
		env.put("DSTAT_MYSQL_PORT", dbPort);
		env.put("DSTAT_OUTPUT_PATH", sysLogPath);
		dstatProcess = pb.start();
		Log.debug("dstat started.");
	}

	private void runTailer() throws Exception
	{
		dbLogFile = new File(dbLogPath);
		sysLogFile = new File(sysLogPath);

		LogTailerListener dbLogListener = new LogTailerListener(dbLogQueue);
		LogTailerListener sysLogListener = new LogTailerListener(sysLogQueue);

		// starts from the last line for db log.
		dbLogTailer = new LogTailer(dbLogFile, dbLogListener, 250, -1);
		// starts from the beginning for dstat.
		sysLogTailer = new LogTailer(sysLogFile, sysLogListener, 250, 0);

		tailerExecutor = Executors.newFixedThreadPool(3); // 1 more just in case
		tailerExecutor.submit(dbLogTailer);
		tailerExecutor.submit(sysLogTailer);
	}

	private boolean testMySQLConnection()
	{
		String url = String.format("jdbc:mysql://%s:%s/%s", dbHost, dbPort, testDB);
		boolean canConnect = false;
		try
		{
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			Connection conn = (Connection) DriverManager.getConnection(url, dbUser, dbPassword);

			// connection was successful.
			conn.close();
			canConnect = true;
		}
		catch (IllegalAccessException e)
		{
			e.printStackTrace();
		}
		catch (InstantiationException e)
		{
			e.printStackTrace();
		}
		catch (ClassNotFoundException e)
		{
			e.printStackTrace();
		}
		catch (SQLException e)
		{
			Log.debug("Caught a SQLException while testing connection to the server.");
		}

		return canConnect;
	}

	public ArrayBlockingQueue<String> getDbLogQueue()
	{
		return dbLogQueue;
	}

	public ArrayBlockingQueue<String> getSysLogQueue()
	{
		return sysLogQueue;
	}

	public static void main(String[] args)
	{
		// set up logger
		Log.set(Log.LEVEL_DEBUG); // DEBUG for now.

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

		options.addOption(configOption);
		options.addOption(helpOption);

		HelpFormatter formatter = new HelpFormatter();

		try
		{
			CommandLine line = clParser.parse(options, args);
			int port = 3555; // default port
			String dbLogPath, sysLogPath, dbUser, dbPassword, dbHost, dbPort, testDB;
			testDB = "test"; // default test DB

			if (line.hasOption("h"))
			{
				formatter.printHelp("MiddlewareServer", options, true);
				return;
			}
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
			String testDBStr = section.get("testDB");
			if (testDBStr != null)
			{
				testDB = testDBStr;
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

			MiddlewareServer server = new MiddlewareServer(port, dbLogPath, sysLogPath, dbUser, dbPassword, dbHost, dbPort, testDB);
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
}
