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
import dbseer.middleware.packet.MiddlewarePacket;
import dbseer.middleware.packet.MiddlewarePacketDecoder;
import dbseer.middleware.packet.MiddlewarePacketEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
	private int reqId;
	private boolean isMonitoring;

	private String logPath;

	private Channel channel = null;
	private ExecutorService requesterExecutor = null;

	private ExecutorService heartbeatSenderExecutor = null;
	private MiddlewareClientHeartbeatSender heartbeatSender = null;

	private MiddlewareClientLogRequester txLogRequester = null;
	private Map<String, MiddlewareClientLogRequester> sysLogRequester = null;
	private Map<String, PrintWriter> logWriterMap = null; // tx log writer for dbseer: <transaction type, writer>
	private Map<Integer, String> statementMessageMap = null;

	private ArrayList<String> serverNameList = null;
	private ZipOutputStream txZipOutputStream = null;
	private PrintWriter txPrintWriter = null;
	private File txLogFileRaw = null;

	public MiddlewareClient(String host, String id, String password, int port, String logPath)
	{
		this.retry = 0;
		this.reqId = 0;
		this.id = id;
		this.password = password;
		this.host = host;
		this.port = port;
		this.logPath = logPath;
		this.isMonitoring = false;
		this.logWriterMap = new HashMap<>();
		this.sysLogRequester = new HashMap<>();
		this.statementMessageMap = new HashMap<>();
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
			// attach shutdown hook.
			MiddlewareClientShutdown shutdownThread = new MiddlewareClientShutdown(this);
			Runtime.getRuntime().addShutdownHook(shutdownThread);

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
					.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
					.handler(new ChannelInitializer<SocketChannel>()
					{
						@Override
						protected void initChannel(SocketChannel ch) throws Exception
						{
							ChannelPipeline p = ch.pipeline();
							p.addLast(new IdleStateHandler(10, 0, 0));
							p.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB));
							p.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.ZLIB));
							p.addLast(new MiddlewarePacketDecoder());
							p.addLast(new MiddlewarePacketEncoder());
							p.addLast(new MiddlewareClientHandler(client));
						}
					});

			ChannelFuture f = b.connect(host, port).sync();
			channel = f.channel();
			Log.debug("Connected to the middleware.");

			MiddlewarePacket checkPacket = new MiddlewarePacket(MiddlewareConstants.PACKET_CHECK_VERSION, MiddlewareConstants.PROTOCOL_VERSION);
//			ByteBuf buf = Unpooled.buffer();
//			buf.writeInt(MiddlewareConstants.PACKET_CHECK_VERSION);
//			buf.writeInt(MiddlewareConstants.PROTOCOL_VERSION.getBytes("UTF-8").length);
//			buf.writeBytes(MiddlewareConstants.PROTOCOL_VERSION.getBytes("UTF-8"));
//			channel.writeAndFlush(buf);
			channel.writeAndFlush(checkPacket);

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
			if (txLogFileRaw.exists())
			{
				txLogFileRaw.delete();
			}
			if (txZipOutputStream != null)
			{
				try
				{
					txZipOutputStream.closeEntry();
					txZipOutputStream.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
				txZipOutputStream = null;
			}
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
//			ByteBuf b = Unpooled.buffer();
//			b.writeInt(MiddlewareConstants.PACKET_START_MONITORING);
//			b.writeInt(idPassword.getBytes("UTF-8").length);
//			b.writeBytes(idPassword.getBytes("UTF-8"));
//			channel.writeAndFlush(b);

			MiddlewarePacket packet = new MiddlewarePacket(MiddlewareConstants.PACKET_START_MONITORING, idPassword);
			channel.writeAndFlush(packet);
		}
		Log.debug("Start monitoring packet sent.");
		retry++;
	}

	public void stopMonitoring() throws Exception
	{
		this.stopExecutors();
		if (channel != null)
		{
//			ByteBuf b = Unpooled.buffer();
//			b.writeInt(MiddlewareConstants.PACKET_STOP_MONITORING);
//			b.writeInt(0);
//			channel.writeAndFlush(b);

			MiddlewarePacket packet = new MiddlewarePacket(MiddlewareConstants.PACKET_STOP_MONITORING);
			channel.writeAndFlush(packet);
		}
		Log.debug("Stop monitoring packet sent.");

		if (txLogFileRaw != null && txLogFileRaw.exists())
		{
			txLogFileRaw.delete();
		}
		if (txZipOutputStream != null)
		{
			txZipOutputStream.closeEntry();
			txZipOutputStream.close();
			txZipOutputStream = null;
		}

		// reset retry count.
		retry = 0;
		isMonitoring = false;
	}

	public File getTxLogFileRaw()
	{
		return txLogFileRaw;
	}

	public void requestServerList() throws Exception
	{
		if (channel != null)
		{
//			ByteBuf b= Unpooled.buffer();
//			b.writeInt(MiddlewareConstants.PACKET_REQUEST_SERVER_LIST);
//			b.writeInt(0);
//			channel.writeAndFlush(b);

			MiddlewarePacket packet = new MiddlewarePacket(MiddlewareConstants.PACKET_REQUEST_SERVER_LIST);
			channel.writeAndFlush(packet);
		}
		Log.debug("Server list request packet sent.");
	}

	public synchronized void requestStatistics(String serverName, int txId, int txType, int stId, long latency, int mode, Set<String> tables, String sql)
	{
		String msg = String.format("%d,%d,%d,%d,%d,%d,", txType, txId, stId, latency, mode, tables.size());
		for (String table : tables)
		{
			msg += table + ",";
		}

		statementMessageMap.put(reqId, msg);

		if (channel != null)
		{
			MiddlewarePacket packet = new MiddlewarePacket(MiddlewareConstants.PACKET_REQUEST_QUERY_STATISTICS, String.format("%s,%d,%s", serverName, reqId, txType, sql));
			channel.writeAndFlush(packet);
		}
		++reqId;
		Log.debug("Table count request packet sent.");
	}

	public void requestTableCount(String serverName, String tableName)
	{
		if (channel != null)
		{
			MiddlewarePacket packet = new MiddlewarePacket(MiddlewareConstants.PACKET_REQUEST_TABLE_COUNT, String.format("%s,%s", serverName, tableName));
			channel.writeAndFlush(packet);
		}
		Log.debug("Table count request packet sent.");
	}

	public void requestNumRowAccessedByQuery(String serverName, int txType, String sql)
	{
		if (channel != null)
		{
			MiddlewarePacket packet = new MiddlewarePacket(MiddlewareConstants.PACKET_REQUEST_NUM_ROW_BY_SQL, String.format("%s,%d,%s", serverName, txType, sql));
			channel.writeAndFlush(packet);
		}
		Log.debug("Num row accessed by sql request packet sent.");
	}

	public synchronized void printQueryStatistics(String serverName, int txType, int reqId, String msg)
	{
		PrintWriter writer = logWriterMap.get(serverName + txType);
		writer.print(statementMessageMap.get(reqId));
		writer.println(msg);
		writer.flush();

		statementMessageMap.remove(reqId);
	}

	public ZipOutputStream startTxLogRequester() throws Exception
	{
		if (requesterExecutor == null)
		{
			requesterExecutor = Executors.newCachedThreadPool();
		}
		txLogRequester =
				new MiddlewareClientLogRequester(channel, MiddlewareConstants.PACKET_REQUEST_TX_LOG);

		requesterExecutor.submit(txLogRequester);

		File dbLogFile = new File(logPath + File.separator + MiddlewareConstants.TX_LOG_ZIP);
		txLogFileRaw = new File(logPath + File.separator + MiddlewareConstants.TX_LOG_RAW);
		txPrintWriter = new PrintWriter(new FileWriter(txLogFileRaw, false));
		FileOutputStream fos = new FileOutputStream(dbLogFile);
		txZipOutputStream = new ZipOutputStream(new BufferedOutputStream(fos));

		try
		{
			txZipOutputStream.putNextEntry(new ZipEntry(MiddlewareConstants.TX_LOG_RAW));
		}
		catch (Exception e)
		{
			Log.error(e.getMessage());
			e.printStackTrace();
		}

		Log.debug("Tx Log requester launched.");

		return txZipOutputStream;
	}

	public PrintWriter getTxPrintWriter()
	{
		return txPrintWriter;
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

	public ZipOutputStream getTxZipOutputStream()
	{
		return txZipOutputStream;
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

	public void setTableRowCount(String serverName, String tableName, long rowCount)
	{
		MiddlewareClientEvent event = new MiddlewareClientEvent(MiddlewareClientEvent.TABLE_ROW_COUNT, serverName, tableName, rowCount);
		setChanged();
		notifyObservers(event);
	}

	public boolean isMonitoring()
	{
		return isMonitoring;
	}

	public void registerLogWriter(String id, PrintWriter writer)
	{
		logWriterMap.put(id, writer);
	}

	public void disconnect() throws Exception
	{
		if (channel != null && channel.isActive())
		{
			channel.disconnect();
		}
	}

	public ArrayList<String> getServerNameList()
	{
		return serverNameList;
	}
}
