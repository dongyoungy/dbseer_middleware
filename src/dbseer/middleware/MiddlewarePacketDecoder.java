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
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Created by Dong Young Yoon on 12/2/15.
 */
public class MiddlewarePacketDecoder extends ByteToMessageDecoder
{
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception
	{
		if (buf.readableBytes() < 8)
		{
			Log.debug(this.getClass().getCanonicalName(), "buf less than 8 bytes");
			return;
		}

		buf.markReaderIndex();
		int header = buf.readInt();
		int length = buf.readInt();

		if (buf.readableBytes() < length)
		{
			buf.resetReaderIndex();
			Log.debug(this.getClass().getCanonicalName(), "readable bytes less than length = " + length + " and header = " + header);
			return;
		}
		String log = "";

		if (length > 0)
		{
			log = buf.toString(buf.readerIndex(), length, Charset.defaultCharset());
		}

		out.add(new MiddlewarePacket(header, length, log));
		buf.release();
	}
}
