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

package dbseer.middleware.event;

import java.util.List;

/**
 * Created by Dong Young Yoon on 1/3/16.
 */
public class MiddlewareClientEvent
{
	public static final int IS_MONITORING = 1;
	public static final int IS_NOT_MONITORING = 2;
	public static final int ERROR = 3;

	public int event;
	public String error;
	public String serverStr;

	public MiddlewareClientEvent(int event)
	{
		this.event = event;
	}

	public MiddlewareClientEvent(int event, String error)
	{
		this.event = event;
		this.error = error;
	}
}
