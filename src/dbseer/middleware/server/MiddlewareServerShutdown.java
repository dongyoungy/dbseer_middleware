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

package dbseer.middleware.server;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Created by Dong Young Yoon on 3/31/16.
 */
public class MiddlewareServerShutdown extends Thread
{
	private MiddlewareServer server;

	public MiddlewareServerShutdown(MiddlewareServer server)
	{
		this.server = server;
	}

	@Override
	public void run()
	{
		RandomAccessFile file = server.getNamedPipeFile();
		if (file != null)
		{
			try
			{
				file.writeBytes("0");
				file.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}
}
