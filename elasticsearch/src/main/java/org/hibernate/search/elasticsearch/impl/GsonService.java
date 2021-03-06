/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.elasticsearch.impl;

import org.hibernate.search.engine.service.spi.Service;

import com.google.gson.Gson;

/**
 * Centralizes the configuration of the Gson object.
 *
 * @author Guillaume Smet
 */
public interface GsonService extends Service {

	Gson getGson();

	/**
	 * @return Same as {@link #getGson()}, but with pretty-printing turned on. Useful for logging.
	 */
	Gson getGsonPrettyPrinting();

	/**
	 * @return Same as {@link #getGson()}, but with null serialization turned off.
	 */
	Gson getGsonNoSerializeNulls();

}
