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

package dbseer.middleware.data;

import com.esotericsoftware.minlog.Log;
import dbseer.middleware.constant.MiddlewareConstants;
import dbseer.middleware.log.LogTailer;
import dbseer.middleware.log.LogTailerListener;

import java.io.File;
import java.util.*;
import java.sql.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by Dong Young Yoon on 1/10/16.
 *
 * Server that the middleware monitors (different from MiddlewareServer itself)
 */
public class Server
{
	String name;

	String dbHost;
	String dbPort;
	String dbName;
	String dbUser;
	String dbPassword;

	String sshUser;

	String monitorDir;
	String monitorScript;
	String logPath;
	String url;

	Process monitorProcess;
	File logFile;

	Connection conn;

//	private ArrayBlockingQueue<String> logQueue;
	private LinkedBlockingQueue<String> logQueue;
	private LogTailerListener logTailerListener;
	private LogTailer logTailer;
	private ExecutorService tailerExecutor;

	List<String> tableList;
	Map<String, Long> tableCount;

	public Server(String name, String dbHost, String dbPort, String dbName, String dbUser, String dbPassword, String sshUser, String monitorDir, String monitorScript, String logPath)
	{
		this.name = name;
		this.dbHost = dbHost;
		this.dbPort = dbPort;
		this.dbUser = dbUser;
		this.dbName = dbName;
		this.dbPassword = dbPassword;
		this.sshUser = sshUser;
		this.monitorDir = monitorDir;
		this.monitorScript = monitorScript;
		this.logPath = logPath;
//		this.logQueue = new ArrayBlockingQueue<>(MiddlewareConstants.QUEUE_SIZE);
		this.url = String.format("jdbc:mysql://%s:%s/%s", dbHost, dbPort, dbName);
		this.logQueue = new LinkedBlockingQueue<>();
		this.tableList = new ArrayList<>();
		this.tableCount = new HashMap<>();
	}

	public void printLogInfo()
	{
		Log.info(String.format("[Server : %s]", name));
		Log.info(String.format("DB Host = %s", dbHost));
		Log.info(String.format("DB Port = %s", dbPort));
		Log.info(String.format("DB Name = %s", dbName));
		Log.info(String.format("DB User = %s", dbUser));
		Log.info(String.format("DB PW = %s", dbPassword));
		Log.info(String.format("SSH User = %s", sshUser));
		Log.info(String.format("Remote Monitor Dir = %s", monitorDir));
		Log.info(String.format("Remote Monitor Script = %s", monitorScript));
	}

	public boolean testConnection()
	{
		boolean canConnect = false;
		try
		{
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			conn = (Connection) DriverManager.getConnection(url, dbUser, dbPassword);

			// connection was successful.
			canConnect = true;
		}
		catch (IllegalAccessException e)
		{
			e.printStackTrace();
		}
		catch (InstantiationException e)
		{
			e.printStackTrace();
		}
		catch (ClassNotFoundException e)
		{
			e.printStackTrace();
		}
		catch (SQLException e)
		{
			Log.debug("Caught a SQLException while testing connection to the server.");
		}

		if (canConnect)
		{
			Log.info("Getting DB statistics");
			if (this.getTableList())
			{
				for (String table : tableList)
				{
					this.getTableCountFromDatabase(table);
				}
			}
		}

		return canConnect;
	}

	private boolean getTableList()
	{
		tableList.clear();
		try
		{
			if (conn == null || conn.isClosed())
			{
				if (!this.testConnection())
				{
					// cannot connect... return -1
					Log.error("No DB Connection.");
					return false;
				}
			}
			String query = String.format("SHOW TABLES IN %s;", dbName);
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			while (rs.next())
			{
				String tableName = rs.getString(1);
				tableList.add(tableName);
			}
		}
		catch (SQLException e)
		{
			Log.debug("Caught a SQLException while getting table names for the database " + dbName);
			return false;
		}
		return true;
	}

	public long getTableCount(String tableName)
	{
		Long count = tableCount.get(tableName);
		if (count == null)
		{
			return getTableCountFromDatabase(tableName);
		}
		else return count.longValue();

	}

	public long getTableCountFromDatabase(String tableName)
	{
		long count = -1;
		try
		{
			if (conn == null || conn.isClosed())
			{
				if (!this.testConnection())
				{
					// cannot connect... return -1
					Log.error("No DB Connection.");
					return count;
				}
			}
			String query = String.format("SELECT COUNT(*) as ROW_COUNT from %s;", tableName);
			Statement stmt = conn.createStatement();
			ResultSet rs = stmt.executeQuery(query);
			if (rs.next())
			{
				count = rs.getInt(1);
			}
			else
			{
				Log.error("No result set");
			}
		}
		catch (SQLException e)
		{
			Log.debug("Caught a SQLException while getting row counts for the table " + tableName);
		}
		if (count != -1)
		{
			tableCount.put(tableName, count);
		}
		return count;
	}

    public boolean testMonitoringDir()
    {
		try
		{
			String sshCmd = "ssh";
			String sshConnection = String.format("%s@%s", sshUser, dbHost);
			String sshEndCmd = String.format("cd %s && ls -l ./%s 1> /dev/null", monitorDir, monitorScript);

			String[] cmds = {sshCmd, sshConnection, sshEndCmd};
			ProcessBuilder pb = new ProcessBuilder(cmds);

			monitorProcess = pb.start();
			int retVal = monitorProcess.waitFor();

			if (retVal != 0)
			{
				return false;
			}
		}
		catch (Exception e)
		{
			 e.printStackTrace();
		}
		return true;
    }

	public void startMonitoring() throws Exception
	{
		if (monitorProcess != null)
		{
			monitorProcess.destroy();
		}

		String sshEndCmd = String.format("cd %s && ./%s 1> /dev/null", monitorDir, monitorScript);

		String sshCmd = "ssh";
		String sshConnection = String.format("%s@%s", sshUser, dbHost);
		String cmd = "";
		cmd += String.format("export DSTAT_MYSQL_USER=%s;", dbUser);
		cmd += String.format("export DSTAT_MYSQL_PWD=%s;", dbPassword);
		cmd += String.format("export DSTAT_MYSQL_HOST=%s;", dbHost);
		cmd += String.format("export DSTAT_MYSQL_PORT=%s;", dbPort);
		cmd += String.format("export DSTAT_OUTPUT_PATH=%s;", "/dev/fd/2");
		cmd += sshEndCmd;

		String[] cmds = {sshCmd, sshConnection, cmd};

		ProcessBuilder pb = new ProcessBuilder(cmds);
		logFile = new File(logPath + File.separator + String.format("sys.log.%s", name));

		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.to(logFile));

		monitorProcess = pb.start();

		// start tailer
		if (tailerExecutor != null)
		{
			tailerExecutor.shutdownNow();
		}

		logTailerListener = new LogTailerListener(logQueue, false);
		logTailer = new LogTailer(logFile, logTailerListener, 250, 0, false);
		tailerExecutor = Executors.newFixedThreadPool(1);
		tailerExecutor.submit(logTailer);

		Log.debug("dstat started remotely.");
	}

	public void stopMonitoring() throws Exception
	{
		if (monitorProcess != null)
		{
			monitorProcess.destroy();
		}

		if (tailerExecutor != null)
		{
			tailerExecutor.shutdownNow();
		}
	}

	public String getDbName()
	{
		return dbName;
	}

	public void setDbName(String dbName)
	{
		this.dbName = dbName;
	}

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public String getDbPort()
	{
		return dbPort;
	}

	public void setDbPort(String dbPort)
	{
		this.dbPort = dbPort;
	}

	public String getDbUser()
	{
		return dbUser;
	}

	public void setDbUser(String dbUser)
	{
		this.dbUser = dbUser;
	}

	public String getSshUser()
	{
		return sshUser;
	}

	public void setSshUser(String sshUser)
	{
		this.sshUser = sshUser;
	}

	public String getDbHost()
	{
		return dbHost;
	}

	public void setDbHost(String dbHost)
	{
		this.dbHost = dbHost;
	}

	public String getMonitorDir()
	{
		return monitorDir;
	}

	public void setMonitorDir(String monitorDir)
	{
		this.monitorDir = monitorDir;
	}

	public String getMonitorScript()
	{
		return monitorScript;
	}

	public void setMonitorScript(String monitorScript)
	{
		this.monitorScript = monitorScript;
	}

	public Process getMonitorProcess()
	{
		return monitorProcess;
	}

	public void setMonitorProcess(Process monitorProcess)
	{
		this.monitorProcess = monitorProcess;
	}

	public LogTailerListener getLogTailerListener()
	{
		return logTailerListener;
	}

	public LinkedBlockingQueue<String> getLogQueue()
	{
		return logQueue;
	}
}
