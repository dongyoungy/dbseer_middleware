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

package dbseer.middleware.test;

import com.esotericsoftware.minlog.Log;
import dbseer.middleware.client.MiddlewareClient;
import org.apache.commons.cli.*;

import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by Dong Young Yoon on 12/1/15.
 *
 * The test class for testing the middleware client.
 */
public class MiddlewareClientTest
{
	public static void main(String[] args)
	{
		ExecutorService clientExecutor = Executors.newSingleThreadExecutor();

		// set up logger
		Log.set(Log.LEVEL_DEBUG);

		// handle command-line options
		CommandLineParser clParser = new DefaultParser();
		Options options = new Options();

		Option idOption = Option.builder("i")
				.hasArg()
				.argName("ID")
				.required(true)
				.desc("middleware user id")
				.build();

		Option passwordOption = Option.builder("w")
				.hasArg()
				.argName("PASSWORD")
				.required(true)
				.desc("middleware user password")
				.build();

		Option hostOption = Option.builder("h")
				.hasArg()
				.argName("HOST")
				.required(true)
				.desc("middleware hostname")
				.build();

		Option portOption = Option.builder("p")
				.hasArg()
				.argName("PORT")
				.required(true)
				.desc("middleware port")
				.build();

		Option logOption = Option.builder("d")
				.hasArg()
				.argName("FILE")
				.required(true)
				.desc("path to print logs")
				.build();

		Option helpOption = Option.builder("?")
				.longOpt("help")
				.required(false)
				.desc("print this message")
				.build();

		options.addOption(idOption);
		options.addOption(passwordOption);
		options.addOption(hostOption);
		options.addOption(portOption);
		options.addOption(logOption);
		options.addOption(helpOption);

		HelpFormatter formatter = new HelpFormatter();
		try
		{
			CommandLine line = clParser.parse(options, args);
			if (line.hasOption("?"))
			{
				formatter.printHelp("MiddlewareClientTest", options, true);
				return;
			}

			int port;
			String host, logPath, id, password;

			id = line.getOptionValue("i");
			password = line.getOptionValue("w");
			port = Integer.parseInt(line.getOptionValue("p"));
			host = line.getOptionValue("h");
			logPath = line.getOptionValue("d");

			MiddlewareClient client = new MiddlewareClient(host, id, password, port, logPath);
			client.setLogLevel(Log.LEVEL_DEBUG);

			Future clientFuture = clientExecutor.submit(client);
			Thread.sleep(1000);
			Scanner scanner = new Scanner(System.in);
			while (scanner.hasNext())
			{
				String input = scanner.nextLine();
				if (input.equalsIgnoreCase("s"))
				{
					client.startMonitoring();
				}
				else if (input.equalsIgnoreCase("t"))
				{
					client.stopMonitoring();
				}
				else if (input.equalsIgnoreCase("q"))
				{
					clientExecutor.shutdownNow();
					break;
				}
			}
			clientFuture.get();
		}
		catch (ParseException e)
		{
			formatter.printHelp("MiddlewareClientTest", options, true);
			Log.error(e.getMessage());
		}
		catch (Exception e)
		{
			Log.error(e.getMessage());
			e.printStackTrace();
		}

	}
}
