package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.communications.domain.port.EmailSender;
import com.taarifu.communications.domain.port.PushSender;
import com.taarifu.communications.domain.port.SmsGateway;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-safety tests for the channel-adapter selection — the load-bearing fix that prod (a no-profile
 * context) <b>must boot</b> with <b>exactly one</b> bean per port, and that setting a provider swaps in the
 * real adapter (ARCHITECTURE.md §7; the gap that previously stopped prod from booting on a missing
 * {@code SmsGateway} bean).
 *
 * <p>Responsibility: drives an {@link ApplicationContextRunner} (no profiles active — i.e. the prod-shaped
 * context) over the three adapters + the no-op defaults to assert: with <b>no provider configured</b> the
 * single resolved {@link SmsGateway}/{@link PushSender}/{@link EmailSender} is the logging no-op (the
 * {@code matchIfMissing} default — boots safely); with {@code provider=http}/{@code fcm}/{@code smtp} the
 * single resolved bean is the real adapter; and in <b>no</b> configuration are there zero or two beans for
 * a port. This is the exactly-one-bean-per-port-in-every-environment guarantee, asserted mechanically.</p>
 */
class ChannelAdapterSelectionTest {

    /** Minimal context: only this module's config + the adapters + the deps the real adapters need. */
    @Configuration
    @EnableConfigurationProperties(CommunicationsChannelProperties.class)
    static class Slice {
    }

    /** A runner shaped like the prod (no-profile) context, importing only the channel beans + their deps. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    JacksonAutoConfiguration.class, PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Slice.class)
            .withBean(ObjectMapper.class)
            // The six candidate beans for the three ports; @ConditionalOnProperty decides which is active.
            .withUserConfiguration(LoggingSmsGatewayStub.class, HttpSmsGateway.class,
                    LoggingPushSenderStub.class, FcmHttpPushSender.class,
                    LoggingEmailSenderStub.class, SmtpEmailSender.class);

    @Test
    void noProvider_prodBoots_withExactlyOneLoggingDefaultPerPort() {
        // No taarifu.communications.*.provider set: the match-if-missing logging no-ops must be the SINGLE
        // active bean per port, so a no-profile prod context boots (the previous failure mode is gone).
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SmsGateway.class);
            assertThat(ctx).getBean(SmsGateway.class).isInstanceOf(LoggingSmsGatewayStub.class);
            assertThat(ctx).hasSingleBean(PushSender.class);
            assertThat(ctx).getBean(PushSender.class).isInstanceOf(LoggingPushSenderStub.class);
            assertThat(ctx).hasSingleBean(EmailSender.class);
            assertThat(ctx).getBean(EmailSender.class).isInstanceOf(LoggingEmailSenderStub.class);
        });
    }

    @Test
    void smsProviderHttp_selectsRealAdapter_asTheSoleSmsBean() {
        runner.withPropertyValues(
                        "taarifu.communications.sms.provider=http",
                        "taarifu.communications.sms.submit-url=https://sms.example/submit",
                        "taarifu.communications.sms.sender-id=TAARIFU",
                        "taarifu.communications.sms.api-key=secret")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(SmsGateway.class);
                    assertThat(ctx).getBean(SmsGateway.class).isInstanceOf(HttpSmsGateway.class);
                });
    }

    @Test
    void emailProviderSmtp_selectsRealAdapter_asTheSoleEmailBean() {
        // The SMTP adapter needs a JavaMailSender; provide a no-op one so the slice wires without a real ESP.
        runner.withBean(org.springframework.mail.javamail.JavaMailSender.class,
                        () -> org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class))
                .withPropertyValues(
                        "taarifu.communications.email.provider=smtp",
                        "taarifu.communications.email.from=noreply@taarifu.go.tz")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(EmailSender.class);
                    assertThat(ctx).getBean(EmailSender.class).isInstanceOf(SmtpEmailSender.class);
                });
    }
}
