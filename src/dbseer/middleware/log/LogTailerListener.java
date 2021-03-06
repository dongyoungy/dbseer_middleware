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

package dbseer.middleware.log;

import com.esotericsoftware.minlog.Log;
import org.apache.commons.io.input.TailerListenerAdapter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by Dong Young Yoon on 11/30/2015
 */
public class LogTailerListener extends TailerListenerAdapter
{
	boolean discardFirstline;
	private LinkedBlockingQueue<String> queue;
	private String logStr;

	public LogTailerListener(LinkedBlockingQueue<String> queue, boolean discard)
	{
//		this.queue = queue;
		this.logStr = "";
		this.discardFirstline = discard;
	}

	public synchronized void handle(String line, long offset)
	{
		if (discardFirstline)
		{
			discardFirstline = false;
			return;
		}
		
//		if (!queue.offer(line + System.lineSeparator()))
//		{
//			queue.poll();
//			queue.offer(line + System.lineSeparator());
//		}
		logStr = logStr.concat(line).concat(System.lineSeparator());
	}

	public synchronized String getString()
	{
		String returnStr = logStr;
		logStr = "";
		return returnStr;
	}
}
