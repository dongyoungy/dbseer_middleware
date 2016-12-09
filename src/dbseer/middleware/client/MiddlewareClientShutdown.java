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

import java.io.IOException;
import java.util.zip.ZipOutputStream;

/**
 * Created by Dong Young Yoon on 12/8/16.
 */
public class MiddlewareClientShutdown extends Thread
{
	private MiddlewareClient client;

	public MiddlewareClientShutdown(MiddlewareClient client)
	{
		this.client = client;
	}

	@Override
	public void run()
	{
		ZipOutputStream zos = client.getTxZipOutputStream();

		try
		{
			// close zip file.
			if (zos != null)
			{
				zos.closeEntry();
				zos.close();
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
}
