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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

/**
 * Created by Dong Young Yoon on 12/27/15.
 */
public class MiddlewareClientHeartbeatSender implements Runnable
{
	private static final int INTERVAL = 1; // 1 second interval between heartbeats.
	private Channel channel;

	public MiddlewareClientHeartbeatSender(Channel channel)
	{
		this.channel = channel;
	}

	@Override
	public void run()
	{
		// Let's create heartbeat Bytebufs and reuse them.
		ByteBuf heartbeat = Unpooled.buffer();
		heartbeat.writeInt(MiddlewareConstants.PACKET_PING);
		heartbeat.writeInt(0);

		while (true)
		{
			try
			{
				int waitTime = 0;
				while (waitTime < 1000 * INTERVAL)
				{
					Thread.sleep(200);
					if (Thread.currentThread().isInterrupted())
					{
						Log.debug(this.getClass().getCanonicalName(), "interrupted.");
						return;
					}
					waitTime += 200;
				}

				Log.debug("sending a heartbeat");
				channel.write(heartbeat.retain());
				channel.flush();
			}
			catch (Exception e)
			{
				Log.error(this.getClass().getCanonicalName(), "Exception caught while sending heartbeats: " + e.getMessage());
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
