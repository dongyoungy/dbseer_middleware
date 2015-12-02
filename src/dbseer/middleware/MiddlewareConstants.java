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

/**
 * Created by Dong Young Yoon on 12/1/15.
 */
public class MiddlewareConstants
{
	public static final String LOGGER_NAME = "dbseer.middleware.logger";
	public static final int QUEUE_SIZE = 1800; // equi. 30 min logs
	public static final int DB_LOG_TAILER = 1;
	public static final int SYS_LOG_TAILER = 2;

	public static final int PACKET_START_MONITORING = 1;
	public static final int PACKET_START_MONITORING_SUCCESS = 11;
	public static final int PACKET_START_MONITORING_FAILURE = 12;
	public static final int PACKET_REQUEST_DB_LOG = 2;
	public static final int PACKET_DB_LOG = 21;
	public static final int PACKET_REQUEST_SYS_LOG = 3;
	public static final int PACKET_SYS_LOG = 31;
}
