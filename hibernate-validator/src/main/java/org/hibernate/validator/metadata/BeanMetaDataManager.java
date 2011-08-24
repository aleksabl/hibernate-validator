/*
* JBoss, Home of Professional Open Source
* Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.validator.metadata;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.validator.metadata.AggregatedConstrainedElement.ConstrainedElementKind;
import org.hibernate.validator.metadata.AggregatedMethodMetaData.Builder;
import org.hibernate.validator.metadata.provider.AnnotationMetaDataProvider;
import org.hibernate.validator.metadata.provider.MetaDataProvider;
import org.hibernate.validator.util.ReflectionHelper;

import static org.hibernate.validator.util.CollectionHelper.asSet;
import static org.hibernate.validator.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.util.CollectionHelper.newHashMap;
import static org.hibernate.validator.util.CollectionHelper.newHashSet;

/**
 * @author Gunnar Morling
 */
public class BeanMetaDataManager {

	private final List<MetaDataProvider> eagerMetaDataProviders;

	private final ConstraintHelper constraintHelper;

	/**
	 * Used to cache the constraint meta data for validated entities
	 */
	private final BeanMetaDataCache beanMetaDataCache;

	private AnnotationIgnores annotationIgnores;

	//TODO GM: can this be merged with cache?
	private Map<Class<?>, BeanConfiguration<?>> configurationsByClass;

	public BeanMetaDataManager(ConstraintHelper constraintHelper, MetaDataProvider... eagerMetaDataProviders) {
		this( constraintHelper, Arrays.asList( eagerMetaDataProviders ) );
	}

	/**
	 * @param constraintHelper
	 * @param beanMetaDataCache
	 * @param mappingStreams
	 * @param mapping
	 */
	public BeanMetaDataManager(ConstraintHelper constraintHelper, List<MetaDataProvider> eagerMetaDataProviders) {

		this.constraintHelper = constraintHelper;
		this.eagerMetaDataProviders = eagerMetaDataProviders;

		configurationsByClass = newHashMap();
		beanMetaDataCache = new BeanMetaDataCache();

		cacheMetaDataFromEagerProviders();
	}

	private void cacheMetaDataFromEagerProviders() {

		//load meta data from eager providers
		for ( MetaDataProvider oneProvider : eagerMetaDataProviders ) {
			//TODO GM: merge, if also programmatic provider has this option
			if ( oneProvider.getAnnotationIgnores() != null ) {
				annotationIgnores = oneProvider.getAnnotationIgnores();
			}
			addOrMergeAll( oneProvider.getAllBeanConfigurations() );
		}

		if ( annotationIgnores == null ) {
			annotationIgnores = new AnnotationIgnores();
		}

		//load annotation meta data for eagerly configured types and their hierarchy
		Set<Class<?>> preconfiguredClasses = new HashSet<Class<?>>( configurationsByClass.keySet() );

		//TODO GM: don't retrieve annotation meta data several times per type
		for ( Class<?> oneConfiguredClass : preconfiguredClasses ) {
			MetaDataProvider annotationMetaDataProvider = new AnnotationMetaDataProvider(
					constraintHelper, oneConfiguredClass, annotationIgnores
			);
			addOrMergeAll( annotationMetaDataProvider.getAllBeanConfigurations() );
		}

		//store eagerly loaded meta data in cache
		List<BeanMetaDataImpl<?>> allMetaData = configurationAsBeanMetaData();

		for ( BeanMetaDataImpl<?> oneBeanMetaData : allMetaData ) {
			registerWithCache( oneBeanMetaData );
		}
	}

	public <T> BeanMetaData<T> getBeanMetaData(Class<T> beanClass) {
		BeanMetaDataImpl<T> beanMetaData = beanMetaDataCache.getBeanMetaData( beanClass );
		if ( beanMetaData == null ) {
			MetaDataProvider annotationMetaDataProvider = new AnnotationMetaDataProvider(
					constraintHelper, beanClass, annotationIgnores
			);
			addOrMergeAll( annotationMetaDataProvider.getAllBeanConfigurations() );

			beanMetaData = mergeWithMetaDataFromHierarchy( getConfigurationForClass( beanClass ) );

			final BeanMetaDataImpl<T> cachedBeanMetaData = beanMetaDataCache.addBeanMetaData( beanClass, beanMetaData );
			if ( cachedBeanMetaData != null ) {
				beanMetaData = cachedBeanMetaData;
			}
		}
		return beanMetaData;
	}

	private <T> void registerWithCache(BeanMetaDataImpl<T> metaData) {
		beanMetaDataCache.addBeanMetaData( metaData.getBeanClass(), metaData );
	}

	private void addOrMergeAll(Iterable<BeanConfiguration<?>> configurations) {
		for ( BeanConfiguration<?> oneBeanConfiguration : configurations ) {
			addOrMerge( oneBeanConfiguration );
		}
	}

	private <T> void addOrMerge(BeanConfiguration<T> beanConfiguration) {

		BeanConfiguration<T> existingConfiguration = getConfigurationForClass( beanConfiguration.getBeanClass() );

		if ( existingConfiguration == null ) {
			configurationsByClass.put(
					beanConfiguration.getBeanClass(), beanConfiguration
			);
		}
		else {
			existingConfiguration.merge( beanConfiguration );
		}
	}

	@SuppressWarnings("unchecked")
	private <T> BeanConfiguration<T> getConfigurationForClass(Class<T> clazz) {
		return (BeanConfiguration<T>) configurationsByClass.get( clazz );
	}

	/**
	 * @return
	 */
	private List<BeanMetaDataImpl<?>> configurationAsBeanMetaData() {


		List<BeanMetaDataImpl<?>> theValue = newArrayList();

		for ( BeanConfiguration<?> oneConfiguration : configurationsByClass.values() ) {
			theValue.add( mergeWithMetaDataFromHierarchy( oneConfiguration ) );
		}

		return theValue;
	}

	private <T> BeanMetaDataImpl<T> mergeWithMetaDataFromHierarchy(BeanConfiguration<T> rootConfiguration) {

		Class<T> beanClass = rootConfiguration.getBeanClass();

		Set<Builder> builders = newHashSet();

		for ( Class<?> oneHierarchyClass : ReflectionHelper.computeClassHierarchy( beanClass, true ) ) {

			BeanConfiguration<?> configurationForHierarchyClass = getConfigurationForClass( oneHierarchyClass );

			if ( configurationForHierarchyClass == null ) {
				continue;
			}

			for ( ConstrainableElement oneConstrainedElement : configurationForHierarchyClass.getConstrainableElements() ) {
				addMetaDataToBuilder( oneConstrainedElement, builders );
			}
		}

		Set<AggregatedConstrainedElement> aggregatedElements = newHashSet();
		for ( Builder oneBuilder : builders ) {
			aggregatedElements.add( oneBuilder.build() );
		}

		return new BeanMetaDataImpl<T>(
				beanClass,
				rootConfiguration.getDefaultGroupSequence(),
				rootConfiguration.getDefaultGroupSequenceProvider(),
				aggregatedElements
		);
	}

	private void addMetaDataToBuilder(ConstrainableElement constrainableElement, Set<Builder> builders) {
		for ( Builder oneBuilder : builders ) {
			if ( oneBuilder.accepts( constrainableElement ) ) {
				oneBuilder.add( constrainableElement );
				return;
			}
		}
		
		Set<Builder> builder = Builder.getInstance( constrainableElement, constraintHelper );
		builders.addAll( builder );
	}

	public static abstract class Builder {
		
		public abstract boolean accepts(ConstrainableElement constrainableElement);
		
		public abstract void add(ConstrainableElement constrainableElement);
		
		public abstract AggregatedConstrainedElement build();
		
		public static Set<Builder> getInstance(ConstrainableElement constrainableElement, ConstraintHelper constraintHelper) {
			
			Set<Builder> builders = newHashSet();

			switch(constrainableElement.getConstrainedElementKind()) {
				case FIELD: 
					builders.add(new AggregatedPropertyMetaData.Builder((ConstrainedField) constrainableElement, constraintHelper));
					break;
				case METHOD:
					builders.add( new AggregatedMethodMetaData.Builder((MethodMetaData)constrainableElement) );
					if(((MethodMetaData)constrainableElement).isGetterMethod()) {
						builders.add( new AggregatedPropertyMetaData.Builder( (MethodMetaData)constrainableElement, constraintHelper ));
					}
					break;
					
				case TYPE:
					builders.add( new AggregatedPropertyMetaData.Builder((ConstrainedType) constrainableElement, constraintHelper) );
					break;
				default: 
					throw new IllegalArgumentException();
			}
			
			return builders;
		}

	}
	

}
