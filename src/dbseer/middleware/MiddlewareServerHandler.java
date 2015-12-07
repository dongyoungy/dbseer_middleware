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
		server.stopMonitoring();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception
	{
		super.channelActive(ctx);
		Log.debug("Child handler channel active");
		InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
		if (address != null)
		{
			server.setRemote(address);
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
		if (header == MiddlewareConstants.PACKET_START_MONITORING)
		{
			boolean isStarted;
			isStarted = server.startMonitoring();
			ByteBuf ans = Unpooled.buffer();
			if (isStarted)
			{
				ans.writeInt(MiddlewareConstants.PACKET_START_MONITORING_SUCCESS);
			}
			else
			{
				ans.writeInt(MiddlewareConstants.PACKET_START_MONITORING_FAILURE);
			}
			ans.writeInt(0);
			ctx.writeAndFlush(ans);
		}
		else if (header == MiddlewareConstants.PACKET_REQUEST_DB_LOG)
		{
			String log = "";
			ArrayList<String> logs = new ArrayList<String>();
			server.getDbLogQueue().drainTo(logs);
			for (String aLog : logs)
			{
				log += aLog;
			}
			ByteBuf ans = Unpooled.buffer(8 + log.getBytes("UTF-8").length);
			ans.writeInt(MiddlewareConstants.PACKET_DB_LOG);
			ans.writeInt(log.getBytes("UTF-8").length);
			ans.writeBytes(log.getBytes("UTF-8"));
			ctx.writeAndFlush(ans);
			Log.debug("db log sent");
		}
		else if (header == MiddlewareConstants.PACKET_REQUEST_SYS_LOG)
		{
			String log = "";
			ArrayList<String> logs = new ArrayList<String>();
			server.getSysLogQueue().drainTo(logs);
			for (String aLog : logs)
			{
				log += aLog;
			}
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
