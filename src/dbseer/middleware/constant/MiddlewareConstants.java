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

package dbseer.middleware.constant;

/**
 * Created by Dong Young Yoon on 12/1/15.
 */
public class MiddlewareConstants
{
	public static final int QUEUE_SIZE = 1800; // equi. 30 min logs
	public static final String PROTOCOL_VERSION = "v0.2";

	public static final String SERVER_STRING_DELIMITER = ",";
	public static final String TX_LOG_RAW = "tx.log";
	public static final String TX_LOG_ZIP = "tx.zip";
	public static final String SYS_LOG_PREFIX = "sys.log";

	public static final int PACKET_PING = 0;
	public static final int PACKET_CHECK_VERSION = 10;
	public static final int PACKET_CHECK_VERSION_SUCCESS = 11;
	public static final int PACKET_CHECK_VERSION_FAILURE = 12;

	public static final int PACKET_START_MONITORING = 100;
	public static final int PACKET_START_MONITORING_SUCCESS = 101;
	public static final int PACKET_START_MONITORING_FAILURE = 102;
	public static final int PACKET_STOP_MONITORING = 103;
	public static final int PACKET_STOP_MONITORING_SUCCESS = 104;
	public static final int PACKET_STOP_MONITORING_FAILURE = 105;
	public static final int PACKET_AUTHENTICATION_FAILURE = 106;

	public static final int PACKET_REQUEST_TX_LOG = 200;
	public static final int PACKET_TX_LOG = 201;
	public static final int PACKET_REQUEST_SYS_LOG = 300;
	public static final int PACKET_SYS_LOG = 301;
	public static final int PACKET_CONNECTION_DENIED = 400;

	public static final int PACKET_REQUEST_SERVER_LIST = 500;
	public static final int PACKET_SERVER_LIST = 501;

	public static final int PACKET_REQUEST_TABLE_COUNT = 600;
	public static final int PACKET_TABLE_COUNT = 601;

	public static final int PACKET_REQUEST_NUM_ROW_BY_SQL = 700;
	public static final int PACKET_NUM_ROW_BY_SQL = 701;

	public static final int PACKET_REQUEST_QUERY_STATISTICS = 800;
	public static final int PACKET_QUERY_STATISTICS = 801;
}
