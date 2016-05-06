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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

/**
 * Created by Dong Young Yoon on 12/2/15.
 */
public class MiddlewareClientLogRequester implements Runnable
{
	private static final int SECONDS_TO_TIMEOUT = 10;
	private static final int TIMEOUT = 3; // # of allowed timeouts.
	private volatile boolean isLogReceived = false;

	private Channel channel;
	private volatile int timeout;
	private int packetHeader;
	private String server;

	public MiddlewareClientLogRequester(Channel channel, int packetHeader)
	{
		this.channel = channel;
		this.packetHeader = packetHeader;
		this.timeout = 0;
	}

	public MiddlewareClientLogRequester(Channel channel, int packetHeader, String server)
	{
		this.channel = channel;
		this.packetHeader = packetHeader;
		this.server = server;
		this.timeout = 0;
	}

	public synchronized void logReceived()
	{
		isLogReceived = true;
		timeout = 0;
	}

	@Override
	public void run()
	{
		// Let's create requesting Bytebufs and reuse them.
//		ByteBuf logRequest = Unpooled.buffer();
//		String str = "";
		MiddlewarePacket logRequest = new MiddlewarePacket(packetHeader);

		try
		{
			// initial 0.5 sec delay.
			Thread.sleep(500);

			// write header and length (mandatory!)
//			logRequest.writeInt(packetHeader);
			if (server == null)
			{
//				logRequest.writeInt(0);
				logRequest.length = 0;
			}
			else
			{
//				logRequest.writeInt(server.getBytes("UTF-8").length);
//				logRequest.writeBytes(server.getBytes("UTF-8"));
				logRequest.length = server.getBytes("UTF-8").length;
				logRequest.body = server;
			}
		}
		catch (Exception e)
		{
			Log.error(this.getClass().getCanonicalName(), "Exception caught while sleeping: " + e.getMessage());
			try
			{
				channel.close().sync();
			}
			catch (InterruptedException e1)
			{
				Log.error(this.getClass().getCanonicalName(), "Exception caught while closing connection: " + e1.getMessage());
			}
			return;
		}

		Log.debug("Requester sending first log requests.");
//		channel.write(logRequest.retain());
		channel.write(logRequest);
		channel.flush();

		while (true)
		{
			try
			{
				int waitTime = 0;
				while (waitTime < 1000 * SECONDS_TO_TIMEOUT && !isLogReceived)
				{
					Thread.sleep(200);
					if (Thread.currentThread().isInterrupted())
					{
						Log.debug(this.getClass().getCanonicalName(), "interrupted.");
						return;
					}
					waitTime += 200;
				}

				// if request has timed out, request again.
				if (waitTime >= 1000 * SECONDS_TO_TIMEOUT)
				{
					isLogReceived = true;
					++timeout;
				}

				if (this.timeout >= TIMEOUT)
				{
					throw new Exception(String.format("Log request has timed out for maximum of %d times.", TIMEOUT));
				}

				if (isLogReceived)
				{
					if (packetHeader == MiddlewareConstants.PACKET_REQUEST_TX_LOG)
					{
						Log.debug(String.format("Requester sending SQL performance log requests. (try #%d)", timeout));
					}
					else if (packetHeader == MiddlewareConstants.PACKET_REQUEST_SYS_LOG)
					{
						Log.debug(String.format("Requester sending OS/DBMS stat requests. (try #%d)", timeout));
					}
//					channel.write(logRequest.retain());
					channel.write(logRequest);
					channel.flush();
					isLogReceived = false;
				}
			}
			catch (InterruptedException e)
			{
				// Do nothing here.
				Log.debug(this.getClass().getCanonicalName(), "InterruptedException caught while sleeping: " + e.getMessage());
				return;
			}
			catch (Exception e)
			{
				Log.error(this.getClass().getCanonicalName(), "Exception caught while sleeping: " + e.getMessage());
				try
				{
					channel.close().sync();
				}
				catch (InterruptedException e1)
				{
					Log.error(this.getClass().getCanonicalName(), "Exception caught while closing connection: " + e1.getMessage());
				}
				return;
			}
		}
	}
}
