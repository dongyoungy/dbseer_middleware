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
import dbseer.middleware.packet.MiddlewarePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.PrintWriter;

/**
 * Created by Dong Young Yoon on 12/2/15.
 */
public class MiddlewareClientHandler extends ChannelInboundHandlerAdapter
{
	private MiddlewareClient client;
	private PrintWriter sysLogWriter;
	private PrintWriter dbLogWriter;

	public MiddlewareClientHandler(MiddlewareClient client, PrintWriter sysLogWriter, PrintWriter dbLogWriter)
	{
		this.client = client;
		this.sysLogWriter = sysLogWriter;
		this.dbLogWriter = dbLogWriter;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
	{
		Log.debug("channel read");

		MiddlewarePacket packet = (MiddlewarePacket) msg;

		int header = packet.header;
		if (header == MiddlewareConstants.PACKET_START_MONITORING_SUCCESS)
		{
			Log.debug("start monitoring succeeded.");
			// spawn log requester
			client.startRequester();
			// start heartbeat sender
			client.startHeartbeatSender();
		}
		else if (header == MiddlewareConstants.PACKET_START_MONITORING_FAILURE)
		{
			Log.debug("start monitoring failed.");
			// retry monitoring
			client.startMonitoring();
		}
		else if (header == MiddlewareConstants.PACKET_STOP_MONITORING_SUCCESS)
		{
			Log.debug("stop monitoring succeeded.");
		}
		else if (header == MiddlewareConstants.PACKET_STOP_MONITORING_FAILURE)
		{
			Log.debug("stop monitoring failed.");
		}
		else if (header == MiddlewareConstants.PACKET_DB_LOG)
		{
			Log.debug("received db log.");
			// write db log.
			dbLogWriter.write(packet.body);
			dbLogWriter.flush();
			client.getLogRequester().dbLogReceived();
		}
		else if (header == MiddlewareConstants.PACKET_SYS_LOG)
		{
			Log.debug("received sys log.");
			// write sys log.
			sysLogWriter.write(packet.body);
			sysLogWriter.flush();
			client.getLogRequester().sysLogReceived();
		}
		else if (header == MiddlewareConstants.PACKET_CONNECTION_DENIED)
		{
			Log.debug("connection denied");
			client.getChannel().close().sync();
		}
		else
		{
			Log.error("Unknown packet received: " + packet.header);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
	{
		Log.debug("Child handler exception caught: " + cause.toString());
		ctx.close();
	}

}
