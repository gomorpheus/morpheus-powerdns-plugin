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

import com.morpheusdata.core.DNSProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.projection.NetworkDomainIdentityProjection
import com.morpheusdata.model.projection.NetworkDomainRecordIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.Observable

/**
 * The DNS Provider implementation for Power DNS
 * This contains most methods used for interacting directly with the Power DNS API
 * 
 * @author David Estes
 */
@Slf4j
class PowerDnsProvider implements DNSProvider {

    MorpheusContext morpheusContext
    Plugin plugin

    PowerDnsProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    /**
     * Creates a manually allocated DNS Record of the specified record type on the passed {@link NetworkDomainRecord} object.
     * This is typically called outside of automation and is a manual method for administration purposes.
     * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
     * @param record The domain record that is being requested for creation. All the metadata needed to create teh record
     *               should exist here.
     * @param opts any additional options that may be used in the future to configure behavior. Currently unused
     * @return a ServiceResponse with the success/error state of the create operation as well as the modified record.
     */
    @Override
    ServiceResponse createRecord(AccountIntegration integration, NetworkDomainRecord record, Map opts) {
        log.info("Creating DNS Record via Power DNS...")
        HttpApiClient client = new HttpApiClient()
        Boolean doPointer = (integration.serviceFlag == null || integration.serviceFlag)
        def fqdn = record.fqdn
        try {
            if(!fqdn?.endsWith('.')) {
                fqdn = fqdn + '.'
            }
            String serviceUrl = cleanServiceUrl(integration.serviceUrl)
            String apiPath = cleanApiPath('/' + record.networkDomain.externalId)
            String recordType = record.type
            String token = integration.credentialData?.password ?: integration.servicePassword

            def body = getRecordCreateBody(integration, fqdn, recordType, record.content,record.ttl ?: 86400,doPointer)

            def results = client.callJsonApi(serviceUrl, apiPath, null, null, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json','X-API-KEY':token], ignoreSSL: true,body:body), 'PATCH')

            results.data

            log.info("add record results: ${results}")
            if(results.success){
                record.externalId = body.rrsets[0].name
                return new ServiceResponse<NetworkDomainRecord>(true,null,null,record)
            } else {
                log.error("An error occurred trying to create a dns record {} via {}: Exit {}: {}",fqdn,integration.name, results.exitCode,results.error ?: results.output)
                return new ServiceResponse<NetworkDomainRecord>(false,"Error Creating DNS Record ${results.error}",null,record)
            }
        } catch(e) {
            log.error("createRecord error: ${e}", e)
        } finally {
            client.shutdownClient()
        }
        return ServiceResponse.error("Unknown Error Occurred Creating Power DNS Record",null,record)
    }

    /**
     * Deletes a Zone Record that is specified on the Morpheus side with the target integration endpoint.
     * This could be any record type within the specified integration and the authoritative zone object should be
     * associated with the {@link NetworkDomainRecord} parameter.
     * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
     * @param record The zone record object to be deleted on the target integration.
     * @param opts opts any additional options that may be used in the future to configure behavior. Currently unused
     * @return the ServiceResponse with the success/error of the delete operation.
     */
    @Override
    ServiceResponse deleteRecord(AccountIntegration integration, NetworkDomainRecord record, Map opts) {
        HttpApiClient client = new HttpApiClient()
        try {
            String token = integration.credentialData?.password ?: integration.servicePassword

            def serviceUrl = cleanServiceUrl(integration.serviceUrl)
            def apiPath = cleanApiPath('/' + record.networkDomain.externalId)
            String recordType = record.type
            String fqdn = record.fqdn
            if(!fqdn.endsWith('.')) {
                fqdn = fqdn + '.'
            }

            def body = getRecordDeleteBody(integration, fqdn, recordType, record.content)
            def results = client.callJsonApi(serviceUrl, apiPath, null, null, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json','X-API-KEY':token], ignoreSSL: true,body:body), 'PATCH')


            log.info("delete record results: ${results}")

                if(results.success) {
                    return ServiceResponse.success()
                } else {
                    return ServiceResponse.error("Error removing Power DNS Record ${record.name} - ${results.error}")
                }
        } catch(e) {
            log.error("deleteRecord error: ${e}", e)
            return ServiceResponse.error("System Error removing Power DNS Record ${record.name} - ${e.message}")

        } finally {
            client.shutdownClient()
        }
    }


    /**
     * Periodically called to refresh and sync data coming from the relevant integration. Most integration providers
     * provide a method like this that is called periodically (typically 5 - 10 minutes). DNS Sync operates on a 10min
     * cycle by default. Useful for caching Host Records created outside of Morpheus.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     */
    @Override
    void refresh(AccountIntegration integration) {
        log.info("Refreshing Power DNS")
        HttpApiClient client = new HttpApiClient()
        try {
            def apiUrl = cleanServiceUrl(integration.serviceUrl)
            def apiUri = new URI(apiUrl)
            def apiHost = apiUri.getHost()
            def apiPort = apiUri.getPort() ?: apiUrl?.startsWith('https') ? 443 : 80
            def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, false, true, null)
            def testResults
            // Promise
            if(hostOnline) {
                log.info("Host Online for PowerDns")
                Date now = new Date()
                cacheZones(client,integration)
                cacheZoneRecords(client,integration)
                log.info("Sync Completed in ${new Date().time - now.time}ms")
                morpheus.integration.updateAccountIntegrationStatus(integration, AccountIntegration.Status.ok).subscribe().dispose()
            } else {
                morpheus.integration.updateAccountIntegrationStatus(integration, AccountIntegration.Status.error, 'Power DNS not reachable').subscribe().dispose()
            }
        } catch(e) {
            log.error("refresh PowerDNS error: ${e}", e)
        } finally {
            client.shutdownClient()
        }
    }

    /**
     * Validation Method used to validate all inputs applied to the integration of an DNS Provider upon save.
     * If an input fails validation or authentication information cannot be verified, Error messages should be returned
     * via a {@link ServiceResponse} object where the key on the error is the field name and the value is the error message.
     * If the error is a generic authentication error or unknown error, a standard message can also be sent back in the response.
     * NOTE: This is unused when paired with an IPAMProvider interface
     * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
     * @param opts any custom payload submission options may exist here
     * @return A response is returned depending on if the inputs are valid or not.
     */
    @Override
    ServiceResponse verifyAccountIntegration(AccountIntegration integration, Map opts) {
        try {
            ServiceResponse rtn = new ServiceResponse()

            rtn.errors = [:]
            if(!integration.name || integration.name == ''){
                rtn.errors['name'] = 'name is required'
            }
            if(!integration.serviceUrl || integration.serviceUrl == ''){
                rtn.errors['serviceUrl'] = 'DNS Server is required'
            }
            if((!integration.servicePassword || integration.servicePassword == '') && (!integration.credentialData?.password || integration.credentialData?.password == '')){
                rtn.errors['servicePassword'] = 'password is required'
            }

            if(!integration.serviceVersion || integration.serviceVersion == ''){
                rtn.errors['serviceVersion'] = 'version is required'
            }

            if(rtn.errors.size() > 0){
                rtn.success = false

            } else {
                rtn.success = true
            }

            return rtn
        } catch(e) {
            log.error("validateService error: ${e}", e)
            return ServiceResponse.error(e.message ?: 'unknown error validating dns service')
        }
    }
// Cache Zones methods
    def cacheZones(HttpApiClient client, AccountIntegration integration) {
        try {
            def listResults = listZones(client,integration)
            final Boolean defaultActive = integration?.configMap?.domainActive ? true : false
            if (listResults.success) {
                List apiItems = listResults.results as List<Map>
                Observable<NetworkDomainIdentityProjection> domainRecords = morpheus.network.domain.listIdentityProjections(integration.id)

                SyncTask<NetworkDomainIdentityProjection,Map,NetworkDomain> syncTask = new SyncTask(domainRecords, apiItems as Collection<Map>)
                syncTask.addMatchFunction { NetworkDomainIdentityProjection domainObject, Map apiItem ->
                    domainObject.name == NetworkUtility.getFriendlyDomainName(apiItem.name as String)
                }.onDelete {removeItems ->
                    morpheus.network.domain.remove(integration.id, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingZones(integration, itemsToAdd,defaultActive)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainIdentityProjection,Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomain networkDomain ->
                        SyncTask.UpdateItemDto<NetworkDomainIdentityProjection, Map> matchItem = updateItemMap[networkDomain.id]
                        return new SyncTask.UpdateItem<NetworkDomain,Map>(existingItem:networkDomain, masterItem:matchItem.masterItem)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomain,Map>> updateItems ->
                    updateMatchedZones(integration, updateItems)
                }.start()
            }
        } catch (e) {
            log.error("cacheZones error: ${e}", e)
        }
    }

    /**
     * Creates a mapping for networkDomainService.createSyncedNetworkDomain() method on the network context.
     * @param poolServer
     * @param addList
     */
    void addMissingZones(AccountIntegration integration, Collection addList, Boolean defaultActive) {
        List<NetworkDomain> missingZonesList = addList?.collect { Map zone ->
            NetworkDomain networkDomain = new NetworkDomain()
            networkDomain.externalId = zone.url
            networkDomain.name = NetworkUtility.getFriendlyDomainName(zone.name as String)
            networkDomain.fqdn = NetworkUtility.getFqdnDomainName(zone.name as String)
            networkDomain.refSource = 'integration'
            networkDomain.zoneType = zone.kind
            networkDomain.publicZone = true
            networkDomain.active = defaultActive
            networkDomain.domainSerial = zone.serial
            networkDomain.dnssec = zone.dnssec
            return networkDomain
        }
        morpheus.network.domain.create(integration.id, missingZonesList).blockingGet()
    }

    /**
     * Given a pool server and updateList, extract externalId's and names to match on and update NetworkDomains.
     * @param poolServer
     * @param addList
     */
    void updateMatchedZones(AccountIntegration integration, List<SyncTask.UpdateItem<NetworkDomain,Map>> updateList) {
        def domainsToUpdate = []
        for(SyncTask.UpdateItem<NetworkDomain,Map> update in updateList) {
            NetworkDomain existingItem = update.existingItem as NetworkDomain
            if(existingItem) {
                Boolean save = false
                if(!existingItem.externalId) {
                    existingItem.externalId = update.masterItem.url
                    save = true
                }

                if(!existingItem.refId) {
                    existingItem.refType = 'AccountIntegration'
                    existingItem.refId = integration.id
                    existingItem.refSource = 'integration'
                    save = true
                }

                if(save) {
                    domainsToUpdate.add(existingItem)
                }
            }
        }
        if(domainsToUpdate.size() > 0) {
            morpheus.network.domain.save(domainsToUpdate).blockingGet()
        }
    }


    // Cache Zones records
    def cacheZoneRecords(final HttpApiClient client, final AccountIntegration integration) {
        morpheus.network.domain.listIdentityProjections(integration.id).buffer(50).concatMap { Collection<NetworkDomainIdentityProjection> poolIdents ->
            return morpheus.network.domain.listById(poolIdents.collect{it.id})
        }.concatMap { NetworkDomain domain ->
            def listResults = listRecords(client, integration,domain)


            if (listResults.success) {
                List<Map> apiItems = getRecordSetResults(integration,listResults) as  List<Map>
                Observable<NetworkDomainRecordIdentityProjection> domainRecords = morpheus.network.domain.record.listIdentityProjections(domain,null)
                SyncTask<NetworkDomainRecordIdentityProjection, Map, NetworkDomainRecord> syncTask = new SyncTask<NetworkDomainRecordIdentityProjection, Map, NetworkDomainRecord>(domainRecords, apiItems)
                return syncTask.addMatchFunction {  NetworkDomainRecordIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == "${apiItem.type?.toUpperCase()}:${apiItem.name}"
                }.addMatchFunction{  NetworkDomainRecordIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.name
                }.onDelete {removeItems ->
                    morpheus.network.domain.record.remove(domain, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingDomainRecords(integration,domain, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection,Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.record.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomainRecord domainRecord ->
                        SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection, Map> matchItem = updateItemMap[domainRecord.id]
                        return new SyncTask.UpdateItem<NetworkDomainRecord,Map>(existingItem:domainRecord, masterItem:matchItem.masterItem)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomainRecord,Map>> updateItems ->
                    updateMatchedDomainRecords(integration, updateItems)
                }.observe()
            } else {
                return Single.just(false)
            }
        }.doOnError{ e ->
            log.error("cacheZoneRecords error: ${e}", e)
        }.blockingSubscribe()

    }


    void updateMatchedDomainRecords(AccountIntegration integration, List<SyncTask.UpdateItem<NetworkDomainRecord, Map>> updateList) {
        def records = []
        updateList?.each { update ->
            NetworkDomainRecord existingItem = update.existingItem
            if(existingItem) {
                def correctExternal = "${update.masterItem.type.toUpperCase()}:${update.masterItem.name}"
                //update view ?
                Boolean save = false
                def recordContent = getRecordContent(integration, update.masterItem)
                if(existingItem.content != recordContent) {
                    existingItem.content = recordContent
                    save = true
                }
                if(existingItem.externalId != correctExternal) {
                    existingItem.externalId = correctExternal
                    save = true
                }


                if(save) {
                    records.add(existingItem)
                }
            }
        }
        if(records.size() > 0) {
            morpheus.network.domain.record.save(records).blockingGet()
        }
    }

    void addMissingDomainRecords(AccountIntegration integration, NetworkDomain domain, Collection<Map> addList) {
        List<NetworkDomainRecord> records = []

        addList?.each {record ->
            def addConfig = [networkDomain:domain, fqdn:NetworkUtility.getFqdnDomainName(record.name),
                             type:record.type?.toUpperCase(), comments:cleanComments(record.comments), ttl:record.ttl, content:getRecordContent(integration, record),
                             externalId:"${record.type?.toUpperCase()}:${record.name}", source:'sync']
            if(addConfig.type == 'SOA' || addConfig.type == 'NS')
                addConfig.name = record.name
            else
                addConfig.name = NetworkUtility.getDomainRecordName(record.name as String, domain.fqdn)
            def add = new NetworkDomainRecord(addConfig)
            records.add(add)
        }
        morpheus.network.domain.record.create(domain,records).blockingGet()
    }


    /**
     * Provide custom configuration options when creating a new {@link AccountIntegration}
     * @return a List of OptionType
     */
    @Override
    List<OptionType> getIntegrationOptionTypes() {
        return [
                new OptionType(code: 'accountIntegration.powerDns.serviceUrl', name: 'Service URL', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUrl', fieldLabel: 'API Url', fieldContext: 'domain', required:true,helpText: 'Warning! Using HTTP URLS are insecure and not recommended.', helpTextI18nCode:'gomorpheus.help.serviceUrl', displayOrder: 0),
                new OptionType(code: 'accountIntegration.powerDns.credentials', name: 'Credentials', inputType: OptionType.InputType.CREDENTIAL, fieldName: 'type', fieldLabel: 'Credentials', fieldContext: 'credential', required: true, displayOrder: 1, defaultValue: 'local',optionSource: 'credentials',config: '{"credentialTypes":["api-key"]}'),
                new OptionType(code: 'accountIntegration.powerDns.servicePassword', name: 'Token', inputType: OptionType.InputType.PASSWORD, fieldName: 'servicePassword', fieldLabel: 'Token', fieldContext: 'domain', required:true, displayOrder: 3,localCredential: true),
                new OptionType(code:'accountIntegration.powerDns.serviceVersion', inputType: OptionType.InputType.SELECT, name:'serviceVersion', category:'accountIntegration.powerDns', optionSource: 'powerDnsVersion',
                        fieldName:'serviceVersion', fieldCode: 'gomorpheus.label.version', fieldLabel:'Service Version', fieldContext:'domain', required:true, enabled:true, editable:true, global:false,
                        placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:75),
                new OptionType(code:'accountIntegration.powerDns.serviceFlag', inputType: OptionType.InputType.CHECKBOX, name:'serviceFlag', category:'accountIntegration.powerDns',
                        fieldName:'serviceFlag', fieldCode: 'gomorpheus.label.dnsPointerCreate', fieldLabel:'Create Pointers', fieldContext:'domain', required:false, enabled:true, editable:true, global:false,
                        placeHolder:null, helpBlock:'', defaultValue:'on', custom:false, displayOrder:80),
                new OptionType(code: 'accountIntegration.powerDns.domainActive', name: 'Domain Active', inputType: OptionType.InputType.CHECKBOX, defaultValue: true, fieldName: 'domainActive', fieldLabel: 'Domain Active', fieldContext: 'config', displayOrder: 85)

        ]
    }

    /**
     * Returns the DNS Integration logo for display when a user needs to view or add this integration
     * @since 0.12.3
     * @return Icon representation of assets stored in the src/assets of the project.
     */
    @Override
    Icon getIcon() {
        return new Icon(path:"powerDns.svg", darkPath: "powerDns-white.svg")
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
     *
     * @return an implementation of the MorpheusContext for running Future based rxJava queries
     */
    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    /**
     * Returns the instance of the Plugin class that this provider is loaded from
     * @return Plugin class contains references to other providers
     */
    @Override
    Plugin getPlugin() {
        return plugin
    }

    /**
     * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
     * that is seeded or generated related to this provider will reference it by this code.
     * @return short code string that should be unique across all other plugin implementations.
     */
    @Override
    String getCode() {
        return "powerDns"
    }

    /**
     * Provides the provider name for reference when adding to the Morpheus Orchestrator
     * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
     *
     * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
     */
    @Override
    String getName() {
        return "PowerDNS"
    }



    private listZones(HttpApiClient client, AccountIntegration integration, Map opts = [:]) {
        def rtn = [success:false, errors: [:]]
        def serviceUrl = cleanServiceUrl(integration.serviceUrl)
        def apiPath = getApiPath(integration, '/servers/localhost/zones')
        String token = integration.credentialData?.password ?: integration.servicePassword
        def results = client.callJsonApi(serviceUrl, apiPath, null, null, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json','X-API-KEY':token], ignoreSSL: true), 'GET')
        rtn.success = results?.success && !results?.error
        log.debug("getItem results: ${results}")
        if(rtn.success) {
            rtn.results = results.data
            rtn.headers = results.headers
        } else {
            rtn.msg = results.error
        }
        return rtn
    }

    private listRecords(HttpApiClient client, AccountIntegration integration, NetworkDomain domain, Map opts = [:]) {
        def rtn = [success:false]
        String token = integration.credentialData?.password ?: integration.servicePassword
        def serviceUrl = cleanServiceUrl(integration.serviceUrl)
        if(domain && domain.externalId) {
            def apiPath = cleanApiPath('/' + domain.externalId)
            Map<String,String> query = [:]
            if(opts.max)
                query.max = opts.max.toString()
            if(opts.phrase)
                query.q = opts.phrase.toString()

            def results = client.callJsonApi(serviceUrl, apiPath, null, null, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json','X-API-KEY':token], ignoreSSL: true, queryParams: query), 'GET')


            rtn.success = results?.success && results?.error != true
            log.debug("getItem results: ${results}")
            if(rtn.success) {
                rtn.results = results.data
                rtn.headers = results.headers
            }
            return rtn
        }
        return rtn
    }

    protected getRecordSetResults(AccountIntegration integration, Map data) {
        def rtn
        if(integration.serviceVersion == '3') {
            rtn = data?.results?.records
        } else {
            rtn = data?.results?.rrsets
        }
        return rtn
    }

    protected String cleanApiPath(String path) {
        String rtn = path
        if(rtn?.startsWith('//'))
            rtn = rtn.substring(1)
        return rtn
    }

    protected String cleanServiceUrl(String url) {
        String rtn = url
        def slashIndex = rtn.indexOf('/', 10)
        if(slashIndex > 10)
            rtn = rtn.substring(0, slashIndex)
        return rtn
    }

    protected getApiPath(AccountIntegration integration, String path) {
        def rtn
        if(integration.serviceVersion == '3')
            rtn = path
        else
            rtn = '/api/v1' + path
        return rtn
    }

    def getRecordCreateBody(AccountIntegration integration, String fqdn, String recordType, String content, Integer ttl = 86400,Boolean createPtr=false) {
        def rtn
        if(integration.serviceVersion == '3') {
            def recordName = NetworkUtility.getFriendlyDomainName(fqdn)
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'REPLACE',
                             records:[
                                     [content:content, disabled:false, name:recordName, ttl:ttl, type:recordType, 'set-ptr':createPtr]
                             ]
                            ]
                    ]
            ]
        } else {
            def recordName = fqdn
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'REPLACE',
                             records:[
                                     [content:content, disabled:false]
                             ]
                            ]
                    ]
            ]
        }
        return rtn
    }

    def getRecordDeleteBody(AccountIntegration integration, String fqdn, String recordType, String content, Integer ttl = 86400) {
        def rtn
        if(integration.serviceVersion == '3') {
            def recordName = NetworkUtility.getFriendlyDomainName(fqdn)
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'DELETE',
                             records:[
                                     [content:content, disabled:false, name:recordName, ttl:ttl, type:recordType]
                             ]
                            ]
                    ]
            ]
        } else {
            def recordName = fqdn
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'DELETE',
                             records:[
                                     [content:content, disabled:false]
                             ]
                            ]
                    ]
            ]
        }
        return rtn
    }

    String cleanComments(comments) {
        String rtn = null
        if(comments instanceof List || comments instanceof Map) {
            rtn = JsonOutput.toJson(comments)
        } else if(comments instanceof CharSequence) {
            rtn = comments.toString()
        }
        return rtn
    }

    String getRecordContent(AccountIntegration integration, Map data) {
        String rtn
        if(integration.serviceVersion == '3') {
            rtn = data.content
        } else {
            rtn = data.records?.collect{it.content}?.join('\n')
        }
        return rtn
    }
}
