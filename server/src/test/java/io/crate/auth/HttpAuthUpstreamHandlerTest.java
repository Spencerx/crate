/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.auth;

import static io.crate.auth.HttpAuthUpstreamHandler.WWW_AUTHENTICATE_REALM_MESSAGE;
import static io.crate.role.metadata.RolesHelper.JWT_TOKEN;
import static io.crate.role.metadata.RolesHelper.JWT_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.List;

import javax.net.ssl.SSLSession;

import org.elasticsearch.common.network.DnsResolver;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import io.crate.protocols.postgres.ConnectionProperties;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.role.StubRoleManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.pkitesting.CertificateBuilder;

public class HttpAuthUpstreamHandlerTest extends ESTestCase {

    private final Settings hbaEnabled = Settings.builder()
        .put("auth.host_based.enabled", true)
        .put("auth.host_based.config.0.user", "crate")
        .build();

    // Roles always returns null, so there are no users (even no default crate superuser)
    private final Authentication authService = new HostBasedAuthentication(
        hbaEnabled,
        List::of,
        DnsResolver.SYSTEM,
        () -> "dummy"
    );
    private final HttpAuthUpstreamHandler handlerWithHBA = new HttpAuthUpstreamHandler(Settings.EMPTY, authService, new StubRoleManager());

    private static void assertUnauthorized(DefaultFullHttpResponse resp, String expectedBody) {
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(resp.content().toString(StandardCharsets.UTF_8)).isEqualTo(expectedBody);
        assertThat(resp.headers().get(HttpHeaderNames.WWW_AUTHENTICATE)).isEqualTo(WWW_AUTHENTICATE_REALM_MESSAGE);
    }

    @Test
    public void testChannelClosedWhenUnauthorized() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        HttpAuthUpstreamHandler.sendUnauthorized(ch, null);
        ch.releaseInbound();

        HttpResponse resp = ch.readOutbound();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    public void testSendUnauthorizedWithoutBody() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        HttpAuthUpstreamHandler.sendUnauthorized(ch, null);
        ch.releaseInbound();

        DefaultFullHttpResponse resp = ch.readOutbound();
        assertThat(resp.content()).isEqualTo(Unpooled.EMPTY_BUFFER);
    }

    @Test
    public void testSendUnauthorizedWithBody() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        HttpAuthUpstreamHandler.sendUnauthorized(ch, "not allowed\n");
        ch.releaseInbound();

        DefaultFullHttpResponse resp = ch.readOutbound();
        assertThat(resp.content().toString(StandardCharsets.UTF_8)).isEqualTo("not allowed\n");
    }

    @Test
    public void testSendUnauthorizedWithBodyNoNewline() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        HttpAuthUpstreamHandler.sendUnauthorized(ch, "not allowed");
        ch.releaseInbound();

        DefaultFullHttpResponse resp = ch.readOutbound();
        assertThat(resp.content().toString(StandardCharsets.UTF_8)).isEqualTo("not allowed\n");
    }

    @Test
    public void testAuthorized() throws Exception {
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(
            Settings.EMPTY, new AlwaysOKAuthentication(() -> List.of(Role.CRATE_USER)), new StubRoleManager());
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handler.authorized()).isTrue();
    }

    @Test
    public void testNotNoHbaConfig() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(handlerWithHBA);

        DefaultHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        ch.writeInbound(request);
        ch.releaseInbound();
        assertThat(handlerWithHBA.authorized()).isFalse();

        assertUnauthorized(
            ch.readOutbound(),
            "No valid auth.host_based.config entry found for host \"127.0.0.1\", user \"Aladdin\", protocol \"http\". Did you enable TLS in your client?\n");
    }

    /**
     * Ensure that the {@code X-Real-IP} header is ignored by default as this allows to by-pass HBA rules.
     * See https://github.com/crate/crate/issues/15231.
     */
    @Test
    public void test_real_ip_header_is_ignored_by_default() {
        EmbeddedChannel ch = new EmbeddedChannel(handlerWithHBA);

        DefaultHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        request.headers().add("X-Real-IP", "10.1.0.100");

        ch.writeInbound(request);
        ch.releaseInbound();
        assertThat(handlerWithHBA.authorized()).isFalse();

        assertUnauthorized(
            ch.readOutbound(),
            "No valid auth.host_based.config entry found for host \"127.0.0.1\", user \"Aladdin\", protocol \"http\". Did you enable TLS in your client?\n");
    }

    @Test
    public void test_real_ip_header_is_used_if_enabled() {
        var settings = Settings.builder()
            .put(AuthSettings.AUTH_TRUST_HTTP_SUPPORT_X_REAL_IP.getKey(), true)
            .build();
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(settings, authService, new StubRoleManager());
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        request.headers().add("X-Real-IP", "10.1.0.100");

        ch.writeInbound(request);
        ch.releaseInbound();
        assertThat(handler.authorized()).isFalse();

        assertUnauthorized(
            ch.readOutbound(),
            "No valid auth.host_based.config entry found for host \"10.1.0.100\", user \"Aladdin\", protocol \"http\". Did you enable TLS in your client?\n");
    }

    @Test
    public void test_real_ip_header_blacklist() {
        var settings = Settings.builder()
            .put(AuthSettings.AUTH_TRUST_HTTP_SUPPORT_X_REAL_IP.getKey(), true)
            .build();
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(settings, authService, new StubRoleManager());
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        request.headers().add("X-Real-IP", "::1");

        ch.writeInbound(request);
        ch.releaseInbound();
        assertThat(handler.authorized()).isFalse();

        assertUnauthorized(
            ch.readOutbound(),
            "No valid auth.host_based.config entry found for host \"127.0.0.1\", user \"Aladdin\", protocol \"http\". Did you enable TLS in your client?\n");
    }

    @Test
    public void testUnauthorizedUser() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(handlerWithHBA);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");

        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handlerWithHBA.authorized()).isFalse();
        assertUnauthorized(ch.readOutbound(), "trust authentication failed for user \"crate\"\n");
    }

    @Test
    public void testClientCertUserHasPreferenceOverTrustAuthDefault() throws Exception {
        var ssc = new CertificateBuilder()
            .subject("CN=localhost")
            .setIsCertificateAuthority(true)
            .buildSelfSigned();
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[] { ssc.getCertificate() });

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        String userName = HttpAuthUpstreamHandler.credentialsFromRequest(request, session, Settings.EMPTY).username();

        assertThat(userName).isEqualTo("localhost");
    }

    @Test
    public void testUserAuthenticationWithDisabledHBA() throws Exception {
        Authentication authServiceNoHBA = new AlwaysOKAuthentication(() -> List.of(Role.CRATE_USER));

        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(Settings.EMPTY, authServiceNoHBA, new StubRoleManager());
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Basic Y3JhdGU6");
        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handler.authorized()).isTrue();
    }

    @Test
    public void testUnauthorizedUserWithDisabledHBA() throws Exception {
        Authentication authServiceNoHBA = new AlwaysOKAuthentication(List::of);
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(Settings.EMPTY, authServiceNoHBA, new StubRoleManager());
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handler.authorized()).isFalse();
        assertUnauthorized(ch.readOutbound(), "trust authentication failed for user \"Aladdin\"\n");
    }

    @Test
    public void test_user_authentication_with_jwt_token() throws Exception {
        Roles roles = () -> List.of(JWT_USER);
        Authentication authentication = mock(Authentication.class);
        AuthenticationMethod jwtAuth = mock(JWTAuthenticationMethod.class);
        when(authentication.resolveAuthenticationType(eq(JWT_USER.name()), any(ConnectionProperties.class)))
            .thenReturn(jwtAuth);
        when(jwtAuth.authenticate(any(Credentials.class),any(ConnectionProperties.class))).thenReturn(JWT_USER);

        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(Settings.EMPTY, authentication, roles);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Bearer " + JWT_TOKEN);

        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handler.authorized()).isTrue();
    }

    @Test
    public void test_user_authentication_with_jwt_token_user_not_found() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(handlerWithHBA);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Bearer " + JWT_TOKEN);

        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handlerWithHBA.authorized()).isFalse();
    }

    @Test
    public void test_user_authentication_with_jwt_token_verified_per_connection() throws Exception {
        Roles roles = () -> List.of(JWT_USER);
        Authentication authentication = mock(Authentication.class);
        AuthenticationMethod jwtAuth = mock(JWTAuthenticationMethod.class);
        when(authentication.resolveAuthenticationType(eq(JWT_USER.name()), any(ConnectionProperties.class)))
            .thenReturn(jwtAuth);
        when(jwtAuth.authenticate(any(Credentials.class),any(ConnectionProperties.class))).thenReturn(JWT_USER);

        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(Settings.EMPTY, authentication, roles);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Bearer " + JWT_TOKEN);

        ch.writeInbound(request);
        ch.releaseInbound();

        assertThat(handler.authorized()).isTrue();

        HttpRequest request2 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");
        request2.headers().add(HttpHeaderNames.AUTHORIZATION.toString(), "Bearer " + JWT_TOKEN);
        ch.writeInbound(request2);
        ch.releaseInbound();
        verify(jwtAuth, times(1)).authenticate(any(Credentials.class), any(ConnectionProperties.class));
    }

}
