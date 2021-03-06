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

import java.io.UnsupportedEncodingException;

/**
 * Created by Dong Young Yoon on 12/2/15.
 */
public class MiddlewarePacket
{
	public int header;
	public int length;
	public String body;

	public MiddlewarePacket(int header, int length, String body)
	{
		this.header = header;
		this.length = length;
		this.body = body;
	}

	public MiddlewarePacket(int header, String body)
	{
		this.header = header;
		this.body = body;
		try
		{
			this.length = body.getBytes("UTF-8").length;
		}
		catch (UnsupportedEncodingException e)
		{
			e.printStackTrace();
		}
	}

	public MiddlewarePacket(int header)
	{
		this.header = header;
		this.length = 0;
	}
}
