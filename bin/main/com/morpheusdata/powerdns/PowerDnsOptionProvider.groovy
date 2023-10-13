package com.morpheusdata.powerdns

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin

class PowerDnsOptionProvider extends AbstractOptionSourceProvider{

	MorpheusContext morpheus
	Plugin plugin

	Boolean isPlugin = true //not sure why we need this

	PowerDnsOptionProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.morpheus = morpheusContext
		this.plugin = plugin
	}

	/**
	 * Method names implemented by this provider
	 * @return list of method names
	 */
	@Override
	List<String> getMethodNames() {
		return ['powerDnsVersion']
	}

	def powerDnsVersion(args) {
		[
				[name:'3', value:'3'],
				[name:'4', value:'4']
		]
	}






	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return 'powerDnsOptions'
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'PowerDNS Options'
	}
}
