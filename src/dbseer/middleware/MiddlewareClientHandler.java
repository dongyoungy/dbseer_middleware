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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.PrintWriter;
import java.nio.charset.Charset;

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

		ByteBuf b = (ByteBuf) msg;

		int header = b.readInt();
		if (header == MiddlewareConstants.PACKET_START_MONITORING_SUCCESS)
		{
			Log.debug("start monitoring succeeded.");
			// spawn log requester
			client.startRequester();
		}
		else if (header == MiddlewareConstants.PACKET_START_MONITORING_FAILURE)
		{
			Log.debug("start monitoring failed.");
			// retry monitoring
			client.startMonitoring();
		}
		else if (header == MiddlewareConstants.PACKET_DB_LOG)
		{
			Log.debug("received db log.");
			// write db log.
			int length = b.readInt();
			String log = b.toString(b.readerIndex(), length, Charset.defaultCharset());
			dbLogWriter.write(log);
			dbLogWriter.flush();
		}
		else if (header == MiddlewareConstants.PACKET_SYS_LOG)
		{
			Log.debug("received sys log.");
			// write sys log.
			int length = b.readInt();
			Log.debug("sys log length = " + length);
			String log = b.toString(b.readerIndex(), length, Charset.defaultCharset());
			sysLogWriter.write(log);
			sysLogWriter.flush();
		}
		else
		{
			Log.error("Unknown packet received: " + b.toString());
		}
		b.release();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
	{
		Log.debug("Child handler exception caught");
		cause.printStackTrace();
		ctx.close();
	}

}
