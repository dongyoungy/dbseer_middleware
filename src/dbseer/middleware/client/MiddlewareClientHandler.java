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
import java.util.Map;

/**
 * Created by Dong Young Yoon on 12/2/15.
 */
public class MiddlewareClientHandler extends ChannelInboundHandlerAdapter
{
	private MiddlewareClient client;
	private Map<String,PrintWriter> sysWriter;
	private PrintWriter dbWriter;

	public MiddlewareClientHandler(MiddlewareClient client)
	{
		this.client = client;
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

			// request server list.
			client.requestServerList();
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
			// set monitoring to false
			client.setMonitoring(false);
		}
		else if (header == MiddlewareConstants.PACKET_STOP_MONITORING_FAILURE)
		{
			Log.debug("stop monitoring failed.");
			// set monitoring to false
			client.setMonitoring(false);
		}
		else if (header == MiddlewareConstants.PACKET_SERVER_LIST)
		{
			String serverStr = packet.body;

			// spawn log requester
			dbWriter = client.startTxLogRequester();
			sysWriter = client.startSysLogRequester(serverStr);

			// start heartbeat sender
			client.startHeartbeatSender();
			// set monitoring to true
			client.setMonitoring(true, serverStr);
		}
		else if (header == MiddlewareConstants.PACKET_TX_LOG)
		{
			Log.debug("received db log.");
			// write db log.
			dbWriter.write(packet.body);
			dbWriter.flush();
			client.getTxLogRequester().logReceived();
		}
		else if (header == MiddlewareConstants.PACKET_SYS_LOG)
		{
			Log.debug("received sys log.");

			String[] contents = packet.body.split(MiddlewareConstants.SERVER_STRING_DELIMITER, 2);
			String server = contents[0];
			String log = contents[1];

			// write sys log.
			PrintWriter writer = sysWriter.get(server);
			writer.write(log);
			writer.flush();
			client.getSysLogRequester(server).logReceived();
		}
		else if (header == MiddlewareConstants.PACKET_CONNECTION_DENIED)
		{
			Log.debug("connection denied");
			client.getChannel().close().sync();
			// set monitoring to false
			client.setMonitoring(false);
		}
		else if (header == MiddlewareConstants.PACKET_CHECK_VERSION_SUCCESS)
		{
			Log.debug("check version succeeded.");
			// start monitoring
			client.startMonitoring();
		}
		else if (header == MiddlewareConstants.PACKET_CHECK_VERSION_FAILURE)
		{
			Log.debug("check version failed.");
			client.getChannel().close().sync();
			// set monitoring to false
			client.setMonitoring(false);
		}
		else if (header == MiddlewareConstants.PACKET_PING)
		{
			Log.debug("heartbeat received.");
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
		// set monitoring to false
		client.setMonitoring(false);
		ctx.close();
	}

}
