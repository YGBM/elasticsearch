/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.filter;

import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.shield.audit.AuditTrail;
import org.elasticsearch.shield.license.ShieldLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.junit.annotations.Network;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportSettings;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 *
 */
public class IPFilterTests extends ESTestCase {
    private IPFilter ipFilter;
    private ShieldLicenseState licenseState;
    private AuditTrail auditTrail;
    private Transport transport;
    private HttpServerTransport httpTransport;
    private ClusterSettings clusterSettings;

    @Before
    public void init() {
        licenseState = mock(ShieldLicenseState.class);
        when(licenseState.securityEnabled()).thenReturn(true);
        auditTrail = mock(AuditTrail.class);
        clusterSettings = new ClusterSettings(Settings.EMPTY, new HashSet<>(Arrays.asList(
                IPFilter.HTTP_FILTER_ALLOW_SETTING,
                IPFilter.HTTP_FILTER_DENY_SETTING,
                IPFilter.IP_FILTER_ENABLED_HTTP_SETTING,
                IPFilter.IP_FILTER_ENABLED_SETTING,
                IPFilter.TRANSPORT_FILTER_ALLOW_SETTING,
                IPFilter.TRANSPORT_FILTER_DENY_SETTING,
                TransportSettings.TRANSPORT_PROFILES_SETTING)));

        httpTransport = mock(HttpServerTransport.class);
        InetSocketTransportAddress httpAddress = new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), 9200);
        when(httpTransport.boundAddress()).thenReturn(new BoundTransportAddress(new TransportAddress[] { httpAddress }, httpAddress));
        when(httpTransport.lifecycleState()).thenReturn(Lifecycle.State.STARTED);

        transport = mock(Transport.class);
        InetSocketTransportAddress address = new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), 9300);
        when(transport.boundAddress()).thenReturn(new BoundTransportAddress(new TransportAddress[]{ address }, address));
        when(transport.lifecycleState()).thenReturn(Lifecycle.State.STARTED);

        Map<String, BoundTransportAddress> profileBoundAddresses = Collections.singletonMap("client",
                new BoundTransportAddress(new TransportAddress[]{ new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), 9500) }, address));
        when(transport.profileBoundAddresses()).thenReturn(profileBoundAddresses);
    }

    public void testThatIpV4AddressesCanBeProcessed() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "127.0.0.1")
                .put("shield.transport.filter.deny", "10.0.0.0/8")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsDenied("10.2.3.4");
    }

    public void testThatIpV6AddressesCanBeProcessed() throws Exception {
        // you have to use the shortest possible notation in order to match, so
        // 1234:0db8:85a3:0000:0000:8a2e:0370:7334 becomes 1234:db8:85a3:0:0:8a2e:370:7334
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "2001:0db8:1234::/48")
                .putArray("shield.transport.filter.deny", "1234:db8:85a3:0:0:8a2e:370:7334", "4321:db8:1234::/48")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());

        assertAddressIsAllowed("2001:0db8:1234:0000:0000:8a2e:0370:7334");
        assertAddressIsDenied("1234:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertAddressIsDenied("4321:0db8:1234:0000:0000:8a2e:0370:7334");
    }

    @Network // requires network for name resolution
    public void testThatHostnamesCanBeProcessed() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "127.0.0.1")
                .put("shield.transport.filter.deny", "*.google.com")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());

        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsDenied("8.8.8.8");
    }

    public void testThatAnAllowAllAuthenticatorWorks() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "_all")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsAllowed("173.194.70.100");
    }

    public void testThatProfilesAreSupported() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "localhost")
                .put("shield.transport.filter.deny", "_all")
                .put("transport.profiles.client.shield.filter.allow", "192.168.0.1")
                .put("transport.profiles.client.shield.filter.deny", "_all")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsDenied("192.168.0.1");
        assertAddressIsAllowedForProfile("client", "192.168.0.1");
        assertAddressIsDeniedForProfile("client", "192.168.0.2");
    }

    public void testThatAllowWinsOverDeny() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "10.0.0.1")
                .put("shield.transport.filter.deny", "10.0.0.0/8")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        assertAddressIsAllowed("10.0.0.1");
        assertAddressIsDenied("10.0.0.2");
    }

    public void testDefaultAllow() throws Exception {
        Settings settings = settingsBuilder().build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        assertAddressIsAllowed("10.0.0.1");
        assertAddressIsAllowed("10.0.0.2");
    }

    public void testThatHttpWorks() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "127.0.0.1")
                .put("shield.transport.filter.deny", "10.0.0.0/8")
                .put("shield.http.filter.allow", "10.0.0.0/8")
                .put("shield.http.filter.deny", "192.168.0.1")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundHttpTransportAddress(httpTransport.boundAddress());
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        assertAddressIsAllowedForProfile(IPFilter.HTTP_PROFILE_NAME, "10.2.3.4");
        assertAddressIsDeniedForProfile(IPFilter.HTTP_PROFILE_NAME, "192.168.0.1");
    }

    public void testThatHttpFallsbackToDefault() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.allow", "127.0.0.1")
                .put("shield.transport.filter.deny", "10.0.0.0/8")
                .build();
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundHttpTransportAddress(httpTransport.boundAddress()); 
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());

        assertAddressIsAllowedForProfile(IPFilter.HTTP_PROFILE_NAME, "127.0.0.1");
        assertAddressIsDeniedForProfile(IPFilter.HTTP_PROFILE_NAME, "10.2.3.4");
    }

    public void testThatBoundAddressIsNeverRejected() throws Exception {
        List<String> addressStrings = new ArrayList<>();
        for (TransportAddress address : transport.boundAddress().boundAddresses()) {
            addressStrings.add(NetworkAddress.formatAddress(((InetSocketTransportAddress) address).address().getAddress()));
        }

        Settings settings;
        if (randomBoolean()) {
            settings = settingsBuilder().putArray("shield.transport.filter.deny", addressStrings.toArray(new String[addressStrings.size()])).build();
        } else {
            settings = settingsBuilder().put("shield.transport.filter.deny", "_all").build();
        }
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        ipFilter.setBoundHttpTransportAddress(httpTransport.boundAddress()); 

        for (String addressString : addressStrings) {
            assertAddressIsAllowedForProfile(IPFilter.HTTP_PROFILE_NAME, addressString);
            assertAddressIsAllowedForProfile("default", addressString);
        }
    }

    public void testThatAllAddressesAreAllowedWhenLicenseDisablesSecurity() {
        Settings settings = settingsBuilder()
                .put("shield.transport.filter.deny", "_all")
                .build();
        when(licenseState.securityEnabled()).thenReturn(false);
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());

        // don't use the assert helper because we don't want the audit trail to be invoked here
        String message = String.format(Locale.ROOT, "Expected address %s to be allowed", "8.8.8.8");
        InetAddress address = InetAddresses.forString("8.8.8.8");
        assertThat(message, ipFilter.accept("default", address), is(true));
        verifyZeroInteractions(auditTrail);

        // for sanity enable license and check that it is denied
        when(licenseState.securityEnabled()).thenReturn(true);
        ipFilter = new IPFilter(settings, auditTrail, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());

        assertAddressIsDeniedForProfile("default", "8.8.8.8");
    }

    private void assertAddressIsAllowedForProfile(String profile, String ... inetAddresses) {
        for (String inetAddress : inetAddresses) {
            String message = String.format(Locale.ROOT, "Expected address %s to be allowed", inetAddress);
            InetAddress address = InetAddresses.forString(inetAddress);
            assertThat(message, ipFilter.accept(profile, address), is(true));
            ArgumentCaptor<ShieldIpFilterRule> ruleCaptor = ArgumentCaptor.forClass(ShieldIpFilterRule.class);
            verify(auditTrail).connectionGranted(eq(address), eq(profile), ruleCaptor.capture());
            assertNotNull(ruleCaptor.getValue());
        }
    }

    private void assertAddressIsAllowed(String ... inetAddresses) {
        assertAddressIsAllowedForProfile("default", inetAddresses);
    }

    private void assertAddressIsDeniedForProfile(String profile, String ... inetAddresses) {
        for (String inetAddress : inetAddresses) {
            String message = String.format(Locale.ROOT, "Expected address %s to be denied", inetAddress);
            InetAddress address = InetAddresses.forString(inetAddress);
            assertThat(message, ipFilter.accept(profile, address), is(false));
            ArgumentCaptor<ShieldIpFilterRule> ruleCaptor = ArgumentCaptor.forClass(ShieldIpFilterRule.class);
            verify(auditTrail).connectionDenied(eq(address), eq(profile), ruleCaptor.capture());
            assertNotNull(ruleCaptor.getValue());
        }
    }

    private void assertAddressIsDenied(String ... inetAddresses) {
        assertAddressIsDeniedForProfile("default", inetAddresses);
    }
}
