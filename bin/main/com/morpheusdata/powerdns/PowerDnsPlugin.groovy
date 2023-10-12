/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.morpheusdata.powerdns

import com.morpheusdata.core.Plugin

/**
 * The entrypoint of the Power DNS Plugin. This is where multiple providers can be registered (if necessary).
 * In the case of Power DNS a simple DNS Provider is registered that enables functionality for those areas of automation.
 * 
 * @author David Estes 
 */
class PowerDnsPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-powerdns-plugin'
	}

	@Override
	void initialize() {
		 PowerDnsProvider powerDnsProvider = new PowerDnsProvider(this, morpheus)
		 PowerDnsOptionProvider powerDnsOptionProvider = new PowerDnsOptionProvider(this,morpheus)
		 this.pluginProviders.put("powerDns", powerDnsProvider)
		 this.pluginProviders.put("powerDnsOptions",powerDnsOptionProvider)
		 this.setName("PowerDNS")
	}

	/**
	 * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
	 */
	@Override
	void onDestroy() {
		//nothing to do for now
	}
}
