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

package dbseer.middleware.packet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Created by Dong Young Yoon on 5/6/16.
 */
public class MiddlewarePacketEncoder extends MessageToByteEncoder<MiddlewarePacket>
{
	@Override
	protected void encode(ChannelHandlerContext ctx, MiddlewarePacket packet, ByteBuf buf) throws Exception
	{
		buf.writeInt(packet.header);
		buf.writeInt(packet.length);
		if (packet.length > 0)
		{
			buf.writeBytes(packet.body.getBytes("UTF-8"));
		}
	}
}
