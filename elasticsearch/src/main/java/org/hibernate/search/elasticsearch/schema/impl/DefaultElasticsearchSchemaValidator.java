/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.elasticsearch.schema.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.hibernate.search.elasticsearch.logging.impl.Log;
import org.hibernate.search.elasticsearch.schema.impl.model.DataType;
import org.hibernate.search.elasticsearch.schema.impl.model.DynamicType;
import org.hibernate.search.elasticsearch.schema.impl.model.IndexMetadata;
import org.hibernate.search.elasticsearch.schema.impl.model.IndexType;
import org.hibernate.search.elasticsearch.schema.impl.model.PropertyMapping;
import org.hibernate.search.elasticsearch.schema.impl.model.TypeMapping;
import org.hibernate.search.util.StringHelper;
import org.hibernate.search.util.impl.CollectionHelper;
import org.hibernate.search.engine.service.spi.ServiceManager;
import org.hibernate.search.engine.service.spi.Startable;
import org.hibernate.search.engine.service.spi.Stoppable;
import org.hibernate.search.spi.BuildContext;
import org.hibernate.search.util.logging.impl.LoggerFactory;
import org.jboss.logging.Messages;

import com.google.gson.JsonPrimitive;

/**
 * The default {@link ElasticsearchSchemaValidator} implementation.
 * <strong>Important implementation note:</strong> unexpected attributes (i.e. those not mapped to a field in TypeMapping)
 * are totally ignored. This allows users to leverage Elasticsearch features that are not supported in
 * Hibernate Search, by setting those attributes manually.
 * @author Yoann Rodiere
 */
public class DefaultElasticsearchSchemaValidator implements ElasticsearchSchemaValidator, Startable, Stoppable {

	private static final Log LOG = LoggerFactory.make( Log.class );

	private static final ElasticsearchValidationMessages MESSAGES = Messages.getBundle( ElasticsearchValidationMessages.class );

	private static final double DEFAULT_DOUBLE_DELTA = 0.001;
	private static final float DEFAULT_FLOAT_DELTA = 0.001f;

	private static final List<String> DEFAULT_DATE_FORMAT;
	static {
		List<String> formats = new ArrayList<>();
		formats.add( "strict_date_optional_time" );
		formats.add( "epoch_millis" );
		DEFAULT_DATE_FORMAT = CollectionHelper.toImmutableList( formats );
	}

	private ServiceManager serviceManager;
	private ElasticsearchSchemaAccessor schemaAccessor;

	@Override
	public void start(Properties properties, BuildContext context) {
		serviceManager = context.getServiceManager();
		schemaAccessor = serviceManager.requestService( ElasticsearchSchemaAccessor.class );
	}

	@Override
	public void stop() {
		schemaAccessor = null;
		serviceManager.releaseService( ElasticsearchSchemaAccessor.class );
		serviceManager = null;
	}

	@Override
	public void validate(IndexMetadata expectedIndexMetadata, ExecutionOptions executionOptions) {
		String indexName = expectedIndexMetadata.getName();
		IndexMetadata actualIndexMetadata = schemaAccessor.getCurrentIndexMetadata( indexName );

		ValidationErrorCollector errorCollector = new ValidationErrorCollector();
		errorCollector.setIndexName( indexName );
		try {
			validate( errorCollector, expectedIndexMetadata, actualIndexMetadata );
		}
		finally {
			errorCollector.setIndexName( null );
		}

		Map<ValidationContext, List<String>> messagesByContext = errorCollector.getMessagesByContext();
		if ( messagesByContext.isEmpty() ) {
			return;
		}
		StringBuilder builder = new StringBuilder();
		for ( Map.Entry<ValidationContext, List<String>> entry : messagesByContext.entrySet() ) {
			ValidationContext context = entry.getKey();
			List<String> messages = entry.getValue();

			builder.append( "\n" ).append( formatIntro( context ) );
			for ( String message : messages ) {
				builder.append( "\n\t" ).append( message );
			}
		}
		throw LOG.schemaValidationFailed( builder.toString() );
	}

	private Object formatIntro(ValidationContext context) {
		if ( StringHelper.isNotEmpty( context.getFieldName() ) ) {
			return MESSAGES.errorIntro( context.getIndexName(), context.getMappingName(), context.getPath(), context.getFieldName() );
		}
		else if ( StringHelper.isNotEmpty( context.getPath() ) ) {
			return MESSAGES.errorIntro( context.getIndexName(), context.getMappingName(), context.getPath() );
		}
		else if ( StringHelper.isNotEmpty( context.getMappingName() ) ) {
			return MESSAGES.errorIntro( context.getIndexName(), context.getMappingName() );
		}
		else {
			return MESSAGES.errorIntro( context.getIndexName() );
		}
	}

	private void validate(ValidationErrorCollector errorCollector, IndexMetadata expectedIndexMetadata, IndexMetadata actualIndexMetadata) {
		// Unknown mappings are ignored, we only validate expected mappings
		for ( Map.Entry<String, TypeMapping> entry : expectedIndexMetadata.getMappings().entrySet() ) {
			String mappingName = entry.getKey();
			TypeMapping expectedTypeMapping = entry.getValue();
			TypeMapping actualTypeMapping = actualIndexMetadata.getMappings().get( mappingName );

			errorCollector.setMappingName( mappingName );
			try {
				if ( actualTypeMapping == null ) {
					errorCollector.addError( MESSAGES.mappingMissing() );
					continue;
				}

				validateTypeMapping( errorCollector, expectedTypeMapping, actualTypeMapping );
			}
			finally {
				errorCollector.setMappingName( null );
			}
		}
	}

	private void validateTypeMapping(ValidationErrorCollector errorCollector, TypeMapping expectedMapping, TypeMapping actualMapping) {
		DynamicType expectedDynamic = expectedMapping.getDynamic();
		if ( expectedDynamic != null ) { // If not provided, we don't care
			validateEqualWithDefault( errorCollector, "dynamic", expectedDynamic, actualMapping.getDynamic(), DynamicType.TRUE );
		}
		validateTypeMappingProperties( errorCollector, expectedMapping, actualMapping );
	}

	/**
	 * Validate that two values are equal, using a given default value when null is encountered on either value.
	 * <p>Useful to take into account the fact that Elasticsearch has default values for attributes.
	 */
	private static <T> void validateEqualWithDefault(ValidationErrorCollector errorCollector, String attributeName,
			T expectedValue, T actualValue, T defaultValueForNulls) {
		Object defaultedExpectedValue = expectedValue == null ? defaultValueForNulls : expectedValue;
		Object defaultedActualValue = actualValue == null ? defaultValueForNulls : actualValue;
		if ( ! Objects.equals( defaultedExpectedValue, defaultedActualValue ) ) {
			// Don't show the defaulted actual value, this might confuse users
			errorCollector.addError( MESSAGES.invalidAttributeValue(
					attributeName, defaultedExpectedValue, actualValue
					) );
		}
	}

	/**
	 * Variation of {@link #validateEqualWithDefault(String, Object, Object, Object)} for floats.
	 */
	private static <T> void validateEqualWithDefault(ValidationErrorCollector errorCollector, String attributeName,
			Float expectedValue, Float actualValue, float delta, Float defaultValueForNulls) {
		Float defaultedExpectedValue = expectedValue == null ? defaultValueForNulls : expectedValue;
		Float defaultedActualValue = actualValue == null ? defaultValueForNulls : actualValue;
		if ( defaultedExpectedValue == null || defaultedActualValue == null ) {
			if ( defaultedExpectedValue == defaultedActualValue ) {
				// Both null
				return;
			}
			else {
				// One null and one non-null
				// Don't show the defaulted actual value, this might confuse users
				errorCollector.addError( MESSAGES.invalidAttributeValue(
						attributeName, defaultedExpectedValue, actualValue
						) );
			}
		}
		else {
			if ( Float.compare( defaultedExpectedValue, defaultedActualValue ) == 0 ) {
				return;
			}
			if ( Math.abs( defaultedExpectedValue - defaultedActualValue ) > delta ) {
				// Don't show the defaulted actual value, this might confuse users
				errorCollector.addError( MESSAGES.invalidAttributeValue(
						attributeName, defaultedExpectedValue, actualValue
						) );
			}
		}
	}

	/**
	 * Variation of {@link #validateEqualWithDefault(String, Object, Object, Object)} for doubles.
	 */
	private static <T> void validateEqualWithDefault(ValidationErrorCollector errorCollector, String attributeName,
			Double expectedValue, Double actualValue, double delta, Double defaultValueForNulls) {
		Double defaultedExpectedValue = expectedValue == null ? defaultValueForNulls : expectedValue;
		Double defaultedActualValue = actualValue == null ? defaultValueForNulls : actualValue;
		if ( defaultedExpectedValue == null || defaultedActualValue == null ) {
			if ( defaultedExpectedValue == defaultedActualValue ) {
				// Both null
				return;
			}
			else {
				// One null and one non-null
				// Don't show the defaulted actual value, this might confuse users
				errorCollector.addError( MESSAGES.invalidAttributeValue(
						attributeName, defaultedExpectedValue, actualValue
						) );
			}
		}
		if ( Double.compare( defaultedExpectedValue, defaultedActualValue ) == 0 ) {
			return;
		}
		if ( Math.abs( defaultedExpectedValue - defaultedActualValue ) > delta ) {
			// Don't show the defaulted actual value, this might confuse users
			errorCollector.addError( MESSAGES.invalidAttributeValue(
					attributeName, defaultedExpectedValue, actualValue
					) );
		}
	}

	/**
	 * Special validation for an Elasticsearch format:
	 * <ul>
	 * <li>Checks that the first element (the format used for output format in ES) is equal
	 * <li>Checks all expected formats are present in the actual value
	 * </ul>
	 */
	private static <T> void validateFormatWithDefault(ValidationErrorCollector errorCollector,
			String attributeName, List<String> expectedValue, List<String> actualValue, List<String> defaultValueForNulls) {
		List<String> defaultedExpectedValue = expectedValue == null ? defaultValueForNulls : expectedValue;
		List<String> defaultedActualValue = actualValue == null ? defaultValueForNulls : actualValue;
		if ( defaultedExpectedValue.isEmpty() ) {
			return;
		}

		String expectedOutputFormat = defaultedExpectedValue.get( 0 );
		String actualOutputFormat = defaultedActualValue.isEmpty() ? null : defaultedActualValue.get( 0 );
		if ( ! Objects.equals( expectedOutputFormat, actualOutputFormat ) ) {
			// Don't show the defaulted actual value, this might confuse users
			errorCollector.addError( MESSAGES.invalidOutputFormat(
					attributeName, expectedOutputFormat, actualOutputFormat
					) );
		}

		List<String> missingFormats = new ArrayList<>();
		missingFormats.addAll( defaultedExpectedValue );
		missingFormats.removeAll( defaultedActualValue );

		List<String> unexpectedFormats = new ArrayList<>();
		unexpectedFormats.addAll( defaultedActualValue );
		unexpectedFormats.removeAll( defaultedExpectedValue );

		if ( !missingFormats.isEmpty() || !unexpectedFormats.isEmpty() ) {
			errorCollector.addError( MESSAGES.invalidInputFormat(
					attributeName, defaultedExpectedValue, defaultedActualValue, missingFormats, unexpectedFormats
					) );
		}
	}

	private void validateTypeMappingProperties(ValidationErrorCollector errorCollector,
			TypeMapping expectedMapping, TypeMapping actualMapping) {
		// Unknown properties are ignored, we only validate expected properties
		Map<String, PropertyMapping> expectedPropertyMappings = expectedMapping.getProperties();
		Map<String, PropertyMapping> actualPropertyMappings = actualMapping.getProperties();
		if ( expectedPropertyMappings != null ) {
			for ( Map.Entry<String, PropertyMapping> entry : expectedPropertyMappings.entrySet() ) {
				String propertyName = entry.getKey();
				PropertyMapping expectedPropertyMapping = entry.getValue();
				PropertyMapping actualPropertyMapping = actualPropertyMappings == null ?
						null : actualPropertyMappings.get( propertyName );

				errorCollector.pushPropertyName( propertyName );
				try {
					if ( actualPropertyMapping == null ) {
						errorCollector.addError( MESSAGES.propertyMissing() );
						continue;
					}
					validatePropertyMapping( errorCollector, expectedPropertyMapping, actualPropertyMapping );
				}
				finally {
					errorCollector.popPropertyName();
				}
			}
		}
	}

	private void validatePropertyMapping(ValidationErrorCollector errorCollector,
			PropertyMapping expectedMapping, PropertyMapping actualMapping) {
		validateEqualWithDefault( errorCollector, "type", expectedMapping.getType(), actualMapping.getType(), DataType.OBJECT );

		List<String> formatDefault = DataType.DATE.equals( expectedMapping.getType() )
				? DEFAULT_DATE_FORMAT : Collections.<String>emptyList();
		validateFormatWithDefault( errorCollector, "format", expectedMapping.getFormat(), actualMapping.getFormat(), formatDefault );

		validateEqualWithDefault( errorCollector, "boost", expectedMapping.getBoost(), actualMapping.getBoost(), DEFAULT_FLOAT_DELTA, 1.0f );

		IndexType expectedIndex = expectedMapping.getIndex();
		if ( !IndexType.NO.equals( expectedIndex ) ) { // If we don't need an index, we don't care
			// See Elasticsearch doc: this attribute's default value depends on the data type.
			IndexType indexDefault = DataType.STRING.equals( expectedMapping.getType() ) ? IndexType.ANALYZED : IndexType.NOT_ANALYZED;
			validateEqualWithDefault( errorCollector, "index", expectedIndex, actualMapping.getIndex(), indexDefault );
		}

		Boolean expectedDocValues = expectedMapping.getDocValues();
		if ( Boolean.TRUE.equals( expectedDocValues ) ) { // If we don't need doc_values, we don't care
			/*
			 * Elasticsearch documentation (2.3) says doc_values is true by default on fields
			 * supporting it, but tests show it's wrong.
			 */
			validateEqualWithDefault( errorCollector, "doc_values", expectedDocValues, actualMapping.getDocValues(), false );
		}

		Boolean expectedStore = expectedMapping.getStore();
		if ( Boolean.TRUE.equals( expectedStore ) ) { // If we don't need storage, we don't care
			validateEqualWithDefault( errorCollector, "store", expectedStore, actualMapping.getStore(), false );
		}

		validateJsonPrimitive( errorCollector, expectedMapping.getType(), "null_value",
				expectedMapping.getNullValue(), actualMapping.getNullValue() );

		validateEqualWithDefault( errorCollector, "analyzer", expectedMapping.getAnalyzer(), actualMapping.getAnalyzer(), null );

		validateTypeMapping( errorCollector, expectedMapping, actualMapping );

		validatePropertyMappingFields( errorCollector, expectedMapping, actualMapping );
	}

	private void validatePropertyMappingFields(ValidationErrorCollector errorCollector,
			PropertyMapping expectedMapping, PropertyMapping actualMapping) {
		// Unknown fields are ignored, we only validate expected fields
		Map<String, PropertyMapping> expectedFieldMappings = expectedMapping.getFields();
		Map<String, PropertyMapping> actualFieldMappings = actualMapping.getFields();
		if ( expectedFieldMappings != null ) {
			for ( Map.Entry<String, PropertyMapping> entry : expectedFieldMappings.entrySet() ) {
				String fieldName = entry.getKey();
				PropertyMapping expectedFieldMapping = entry.getValue();

				PropertyMapping actualFieldMapping = actualFieldMappings == null ?
						null : actualFieldMappings.get( fieldName );

				errorCollector.setFieldName( fieldName );
				try {
					if ( actualFieldMapping == null ) {
						errorCollector.addError( MESSAGES.propertyFieldMissing() );
						continue;
					}
					// Validate with the same method as properties, since the content is about the same
					validatePropertyMapping( errorCollector, expectedFieldMapping, actualFieldMapping );
				}
				catch (ElasticsearchSchemaValidationException e) {
					errorCollector.setFieldName( null );
				}
			}
		}
	}

	private static void validateJsonPrimitive(ValidationErrorCollector errorCollector,
			DataType type, String attributeName, JsonPrimitive expectedValue, JsonPrimitive actualValue) {
		DataType defaultedType = type == null ? DataType.OBJECT : type;

		// We can't just use equal, mainly because of floating-point numbers

		switch ( defaultedType ) {
			case DOUBLE:
				if ( expectedValue.isNumber() && actualValue.isNumber() ) {
					validateEqualWithDefault( errorCollector, attributeName, expectedValue.getAsDouble(), actualValue.getAsDouble(),
							DEFAULT_DOUBLE_DELTA, null );
				}
				else {
					errorCollector.addError( MESSAGES.invalidAttributeValue(
							attributeName, expectedValue, actualValue
							) );
				}
				break;
			case FLOAT:
				if ( expectedValue.isNumber() && actualValue.isNumber() ) {
					validateEqualWithDefault( errorCollector, attributeName, expectedValue.getAsFloat(), actualValue.getAsFloat(),
							DEFAULT_FLOAT_DELTA, null );
				}
				else {
					errorCollector.addError( MESSAGES.invalidAttributeValue(
							attributeName, expectedValue, actualValue
							) );
				}
				break;
			case INTEGER:
			case LONG:
			case DATE:
			case BOOLEAN:
			case STRING:
			case OBJECT:
			case GEO_POINT:
			default:
				validateEqualWithDefault( errorCollector, attributeName, expectedValue, actualValue, null );
				break;
		}
	}

}
