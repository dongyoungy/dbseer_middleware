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
import io.netty.channel.Channel;

/**
 * Created by Dong Young Yoon on 12/2/15.
 */
public class MiddlewareClientLogRequester implements Runnable
{
	private static final int DELAY = 1; // request log every second.

	private Channel channel;

	public MiddlewareClientLogRequester(Channel channel)
	{
		this.channel = channel;
	}

	@Override
	public void run()
	{
		// Let's create requesting Bytebufs and reuse them.
		ByteBuf sysLogRequest = Unpooled.buffer();
		ByteBuf dbLogRequest = Unpooled.buffer();
		String str = "";

		sysLogRequest.writeInt(MiddlewareConstants.PACKET_REQUEST_SYS_LOG);
		dbLogRequest.writeInt(MiddlewareConstants.PACKET_REQUEST_DB_LOG);
		sysLogRequest.writeInt(0);
		dbLogRequest.writeInt(0);

		while (true)
		{
			try
			{
				Thread.sleep(1000 * DELAY);
				if (Thread.currentThread().isInterrupted())
				{
					Log.error(this.getClass().getCanonicalName(), "interrupted.");
					break;
				}
			}
			catch (InterruptedException e)
			{
				Log.error(this.getClass().getCanonicalName(), "Exception caught while sleeping: " + e.getMessage());
				break;
			}

			Log.debug("Requester sending log requests.");
			channel.write(sysLogRequest);
//			channel.write(dbLogRequest);
			channel.flush();

			sysLogRequest = sysLogRequest.duplicate().retain();
			dbLogRequest = dbLogRequest.duplicate().retain();
		}
	}
}
