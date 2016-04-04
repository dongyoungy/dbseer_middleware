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
import dbseer.middleware.packet.MiddlewarePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.util.ArrayList;

/**
 * Created by Dong Young Yoon on 12/1/15.
 *
 * The handler class for server.
 */
public class MiddlewareServerHandler extends ChannelInboundHandlerAdapter
{
	private MiddlewareServer server;

	public MiddlewareServerHandler(MiddlewareServer server)
	{
		this.server = server;
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception
	{
		super.channelInactive(ctx);
		Log.debug("Child handler channel inactive");
		if (server.getConnectedChannelGroup().size() == 0)
		{
			server.stopMonitoring();
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception
	{
		super.channelActive(ctx);
		Log.debug("Child handler channel active");
		InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
		if (address != null)
		{
			Log.debug("channel active with: " + address.getHostString());
		}
		else
		{
			Log.debug("channel active but address is null.");
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
	{
		MiddlewarePacket packet = (MiddlewarePacket) msg;
		int header = packet.header;

		Log.debug("Child handler channel read header = " + header);

		if (server.getConnectedChannelGroup().size() > 0 && !server.getConnectedChannelGroup().contains(ctx.channel()))
		{
			ByteBuf ans = Unpooled.buffer();
			ans.writeInt(MiddlewareConstants.PACKET_CONNECTION_DENIED);
			ans.writeInt(0);
			ctx.writeAndFlush(ans);
			return;
		}

		if (server.getConnectedChannelGroup().size() == 0)
		{
			server.getConnectedChannelGroup().add(ctx.channel());
		}

		if (header == MiddlewareConstants.PACKET_START_MONITORING)
		{
			Log.debug("start monitoring");

			// check id and password
			String idPassword = packet.body;
			String[] tokens = idPassword.split("@", 2);
			String receivedId = tokens[0];
			String receivedPassword = tokens[1];

			ByteBuf ans = Unpooled.buffer();

			if (!receivedId.equals(server.getId()) || !receivedPassword.equals(server.getPassword()))
			{
				Log.debug("start monitoring failure: authentication failed.");
				ans.writeInt(MiddlewareConstants.PACKET_AUTHENTICATION_FAILURE);
				String reason = "Authentication failed: ";
				if (!receivedId.equals(server.getId()))
				{
					reason += "Invalid id.";
				}
				else if (!receivedPassword.equals(server.getPassword()))
				{
					reason += "Incorrect password.";
				}
				ans.writeInt(reason.getBytes("UTF-8").length);
				ans.writeBytes(reason.getBytes("UTF-8"));
			}
			else
			{
				// stop monitoring if it is running.
				server.stopMonitoring();

				boolean isStarted;
				isStarted = server.startMonitoring();
				if (isStarted)
				{
					Log.debug("start monitoring success");
					ans.writeInt(MiddlewareConstants.PACKET_START_MONITORING_SUCCESS);
					ans.writeInt(0);
				}
				else
				{
					Log.debug("start monitoring failure");
					ans.writeInt(MiddlewareConstants.PACKET_START_MONITORING_FAILURE);
					ans.writeInt(0);
				}
			}
			ctx.writeAndFlush(ans);
		}
		else if (header == MiddlewareConstants.PACKET_PING)
		{
			ByteBuf ans = Unpooled.buffer();
			ans.writeInt(MiddlewareConstants.PACKET_PING);
			ans.writeInt(0);
			ctx.writeAndFlush(ans);
		}
		else if (header == MiddlewareConstants.PACKET_STOP_MONITORING)
		{
			Log.debug("stop monitoring");
			// stop monitoring
			server.stopMonitoring();

			ByteBuf ans = Unpooled.buffer();
			// check monitoring
			if (server.isMonitoring())
			{
				Log.debug("stop monitoring failure");
				ans.writeInt(MiddlewareConstants.PACKET_STOP_MONITORING_FAILURE);
				ans.writeInt(0);
			}
			else
			{
				Log.debug("stop monitoring success");
				ans.writeInt(MiddlewareConstants.PACKET_STOP_MONITORING_SUCCESS);
				ans.writeInt(0);
			}
			ctx.writeAndFlush(ans);
		}
		else if (header == MiddlewareConstants.PACKET_CHECK_VERSION)
		{
			String clientVersion = packet.body;
			ByteBuf ans = Unpooled.buffer();
			if (clientVersion.equalsIgnoreCase(MiddlewareConstants.PROTOCOL_VERSION))
			{
				ans.writeInt(MiddlewareConstants.PACKET_CHECK_VERSION_SUCCESS);
				ans.writeInt(0);
			}
			else
			{
				ans.writeInt(MiddlewareConstants.PACKET_CHECK_VERSION_FAILURE);
				ans.writeInt(MiddlewareConstants.PROTOCOL_VERSION.getBytes("UTF-8").length);
				ans.writeBytes(MiddlewareConstants.PROTOCOL_VERSION.getBytes("UTF-8"));
			}
			ctx.writeAndFlush(ans);
			Log.debug("check version sent");
		}
		else if (header == MiddlewareConstants.PACKET_REQUEST_SERVER_LIST)
		{
			String serverList = server.getServerList();
			ByteBuf ans = Unpooled.buffer();
			ans.writeInt(MiddlewareConstants.PACKET_SERVER_LIST);
			ans.writeInt(serverList.getBytes("UTF-8").length);
			ans.writeBytes(serverList.getBytes("UTF-8"));
			ctx.writeAndFlush(ans);
			Log.debug("server list sent");
		}
		else if (header == MiddlewareConstants.PACKET_REQUEST_TX_LOG)
		{
//			String log = "";
//			ArrayList<String> logs = new ArrayList<String>();
//			server.getDbLogQueue().drainTo(logs);
//			for (String aLog : logs)
//			{
//				log += aLog;
//			}
			String log = server.getDbLogListener().getString();
			ByteBuf ans = Unpooled.buffer(8 + log.getBytes("UTF-8").length);
			ans.writeInt(MiddlewareConstants.PACKET_TX_LOG);
			ans.writeInt(log.getBytes("UTF-8").length);
			ans.writeBytes(log.getBytes("UTF-8"));
			ctx.writeAndFlush(ans);
			Log.debug("db log sent");
		}
		else if (header == MiddlewareConstants.PACKET_REQUEST_SYS_LOG)
		{
			String serverStr = packet.body;
			String log = serverStr + MiddlewareConstants.SERVER_STRING_DELIMITER;
//			ArrayList<String> logs = new ArrayList<>();
//			server.getServer(serverStr).getLogQueue().drainTo(logs);
//			for (String aLog : logs)
//			{
//				log += aLog;
//			}
			log += server.getServer(serverStr).getLogTailerListener().getString();
			ByteBuf ans = Unpooled.buffer(8 + log.getBytes("UTF-8").length);
			ans.writeInt(MiddlewareConstants.PACKET_SYS_LOG);
			ans.writeInt(log.getBytes("UTF-8").length);
			ans.writeBytes(log.getBytes("UTF-8"));
			ctx.writeAndFlush(ans);
			Log.debug("sys log sent");
		}
		else
		{
			Log.error("Unknown packet received: " + packet.header);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
	{
		Log.debug("Child handler exception caught");
		cause.printStackTrace();
		ctx.close();
	}
}
