package dev.ioexception.dicom.presentation.scp;

import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DicomScpListenerTest {

    @Mock
    private DicomCStoreService dicomCStoreService;

    @Mock
    private ObjectProvider<SslBundles> sslBundlesProvider;

    @Mock
    private SslBundles sslBundles;

    @Mock
    private SslBundle sslBundle;

    @Mock
    private SslManagerBundle sslManagerBundle;

    @Mock
    private X509ExtendedKeyManager keyManager;

    @Mock
    private TrustManager trustManager;

    private DicomScpListener listener;

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stopServer();
        }
    }

    @Test
    @DisplayName("TLS 비활성화(기본값) 상태에서 SCP는 평문 TCP로 바인딩된다")
    void testStartServerWithoutTls() throws Exception {
        listener = new DicomScpListener(dicomCStoreService, sslBundlesProvider);
        ReflectionTestUtils.setField(listener, "serverAet", "TEST_SCP");
        ReflectionTestUtils.setField(listener, "serverPort", 11198);
        ReflectionTestUtils.setField(listener, "allowedAETitles", new String[]{"ANY"});
        ReflectionTestUtils.setField(listener, "maxThreads", 20);
        ReflectionTestUtils.setField(listener, "tlsEnabled", false);
        ReflectionTestUtils.setField(listener, "tlsBundleName", "dicom-bundle");

        listener.startServer();

        Connection conn = (Connection) ReflectionTestUtils.getField(listener, "conn");
        Device device = (Device) ReflectionTestUtils.getField(listener, "device");

        assertThat(conn).isNotNull();
        assertThat(conn.isTls()).isFalse();
        assertThat(device).isNotNull();
        assertThat(device.getKeyManager()).isNull();
        assertThat(device.getTrustManager()).isNull();
    }

    @Test
    @DisplayName("단방향 TLS 활성화(needClientAuth=false) 상태에서 SCP는 KeyManager만 등록하고 클라이언트 인증은 미요구한다")
    void testStartServerWithOneWayTls() throws Exception {
        when(sslBundlesProvider.getIfAvailable()).thenReturn(sslBundles);
        when(sslBundles.getBundle("dicom-bundle")).thenReturn(sslBundle);
        when(sslBundle.getManagers()).thenReturn(sslManagerBundle);
        when(sslManagerBundle.getKeyManagers()).thenReturn(new KeyManager[]{keyManager});

        listener = new DicomScpListener(dicomCStoreService, sslBundlesProvider);
        ReflectionTestUtils.setField(listener, "serverAet", "TEST_SCP_TLS");
        ReflectionTestUtils.setField(listener, "serverPort", 11199);
        ReflectionTestUtils.setField(listener, "allowedAETitles", new String[]{"ANY"});
        ReflectionTestUtils.setField(listener, "maxThreads", 20);
        ReflectionTestUtils.setField(listener, "tlsEnabled", true);
        ReflectionTestUtils.setField(listener, "tlsBundleName", "dicom-bundle");
        ReflectionTestUtils.setField(listener, "needClientAuth", false);

        listener.startServer();

        Connection conn = (Connection) ReflectionTestUtils.getField(listener, "conn");
        Device device = (Device) ReflectionTestUtils.getField(listener, "device");

        assertThat(conn).isNotNull();
        assertThat(conn.isTls()).isTrue();
        assertThat(conn.isTlsNeedClientAuth()).isFalse();
        assertThat(conn.getTlsProtocols()).contains("TLSv1.2", "TLSv1.3");
        assertThat(device).isNotNull();
        assertThat(device.getKeyManager()).isNotNull();
        assertThat(device.getTrustManager()).isNull();
    }

    @Test
    @DisplayName("mTLS 활성화(needClientAuth=true) 상태에서 SCP는 KeyManager와 TrustManager를 모두 등록하고 클라이언트 인증을 요구한다")
    void testStartServerWithMtls() throws Exception {
        when(sslBundlesProvider.getIfAvailable()).thenReturn(sslBundles);
        when(sslBundles.getBundle("dicom-bundle")).thenReturn(sslBundle);
        when(sslBundle.getManagers()).thenReturn(sslManagerBundle);
        when(sslManagerBundle.getKeyManagers()).thenReturn(new KeyManager[]{keyManager});
        when(sslManagerBundle.getTrustManagers()).thenReturn(new TrustManager[]{trustManager});

        listener = new DicomScpListener(dicomCStoreService, sslBundlesProvider);
        ReflectionTestUtils.setField(listener, "serverAet", "TEST_SCP_MTLS");
        ReflectionTestUtils.setField(listener, "serverPort", 11200);
        ReflectionTestUtils.setField(listener, "allowedAETitles", new String[]{"ANY"});
        ReflectionTestUtils.setField(listener, "maxThreads", 20);
        ReflectionTestUtils.setField(listener, "tlsEnabled", true);
        ReflectionTestUtils.setField(listener, "tlsBundleName", "dicom-bundle");
        ReflectionTestUtils.setField(listener, "needClientAuth", true);

        listener.startServer();

        Connection conn = (Connection) ReflectionTestUtils.getField(listener, "conn");
        Device device = (Device) ReflectionTestUtils.getField(listener, "device");

        assertThat(conn).isNotNull();
        assertThat(conn.isTls()).isTrue();
        assertThat(conn.isTlsNeedClientAuth()).isTrue();
        assertThat(conn.getTlsProtocols()).contains("TLSv1.2", "TLSv1.3");
        assertThat(device).isNotNull();
        assertThat(device.getKeyManager()).isNotNull();
        assertThat(device.getTrustManager()).isEqualTo(trustManager);
    }
}
