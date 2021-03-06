/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.analyzer.spi;

/**
 * Reference to a scoped analyzer implementation.
 *
 * @author Davide D'Alto
 *
 * @hsearch.experimental This type is under active development as part of the Elasticsearch integration. You
 * should be prepared for incompatible changes in future releases.
 */
public interface ScopedAnalyzerReference extends AnalyzerReference {

	@Override
	ScopedAnalyzer getAnalyzer();

}
