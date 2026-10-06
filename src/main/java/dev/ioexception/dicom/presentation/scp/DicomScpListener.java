package dev.ioexception.dicom.presentation.scp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.IOException;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "dicom.scp.enabled", havingValue = "true", matchIfMissing = true)
public class DicomScpListener {
    private final DicomCStoreService dicomCStoreService;
    private final ObjectProvider<SslBundles> sslBundlesProvider;

    private Device device;
    private ApplicationEntity ae;
    private Connection conn;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    // 서버 기본 설정값
    @Value("${dicom.scp.aet}")
    private String serverAet;

    @Value("${dicom.scp.port}")
    private int serverPort;

    @Value("${dicom.scp.allowed-aets}")
    private String[] allowedAETitles;

    @Value("${dicom.scp.max-threads}")
    private int maxThreads;

    @Value("${dicom.scp.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${dicom.scp.tls.bundle:dicom-bundle}")
    private String tlsBundleName;

    @Value("${dicom.scp.tls.need-client-auth:false}")
    private boolean needClientAuth;

    @PostConstruct
    public void startServer() throws Exception {
        log.info("DICOM SCP(서버) 초기화를 시작합니다... (TLS: {}, mTLS: {})", tlsEnabled, needClientAuth);

        initDeviceAndConnection();
        initApplicationEntity();
        registerDicomServices();
        initThreadPools();

        device.bindConnections();
        log.info("DICOM SCP(서버)가 포트 {}에서 구동되었습니다. (AETitle: {}, TLS: {}, mTLS: {})", serverPort, serverAet, tlsEnabled, needClientAuth);
    }

    @PreDestroy
    public void stopServer() {
        log.info("DICOM SCP(서버) 종료를 준비합니다...");
        if (device != null) {
            device.unbindConnections();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
        }
        log.info("DICOM SCP(서버)가 안전하게 종료되었습니다.");
    }

    private void initDeviceAndConnection() {
        device = new Device("dicom-server"); // 통신 영향 없음 (로깅/식별용 라벨)
        conn = new Connection();
        conn.setPort(serverPort);
        conn.setBindAddress("0.0.0.0"); // 모든 IP 수신 허용

        if (tlsEnabled) {
            configureTls();
        }
    }

    private void configureTls() {
        log.info("DICOM SCP TLS 모드를 활성화합니다. (SSL Bundle: {}, mTLS(ClientAuth): {})", tlsBundleName, needClientAuth);

        SslBundles sslBundles = sslBundlesProvider.getIfAvailable();
        if (sslBundles == null) {
            throw new IllegalStateException("DICOM SCP TLS가 활성화되었지만 Spring SslBundles 빈을 찾을 수 없습니다.");
        }

        SslBundle sslBundle = sslBundles.getBundle(tlsBundleName);
        KeyManager keyManager = createServerKeyManager(sslBundle);
        device.setKeyManager(keyManager);

        if (needClientAuth) {
            TrustManager trustManager = createTrustManager(sslBundle);
            device.setTrustManager(trustManager);
        } else {
            device.setTrustManager(null);
        }

        conn.setTlsProtocols("TLSv1.2", "TLSv1.3");
        conn.setTlsCipherSuites(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_CBC_SHA"
        );
        conn.setTlsNeedClientAuth(needClientAuth);

        log.info("DICOM SCP TLS 설정 완료 (Protocols: {}, NeedClientAuth: {})",
                (Object) conn.getTlsProtocols(), conn.isTlsNeedClientAuth());
    }

    private TrustManager createTrustManager(SslBundle sslBundle) {
        TrustManager[] trustManagers = sslBundle.getManagers().getTrustManagers();
        if (trustManagers == null || trustManagers.length == 0) {
            throw new IllegalStateException("mTLS(클라이언트 인증)가 활성화되었지만 SSL Bundle '" + tlsBundleName + "'에 TrustManager가 구성되어 있지 않습니다.");
        }
        return trustManagers[0];
    }

    private KeyManager createServerKeyManager(SslBundle sslBundle) {
        KeyManager[] kms = sslBundle.getManagers().getKeyManagers();
        if (kms == null || kms.length == 0) {
            throw new IllegalStateException("SSL Bundle '" + tlsBundleName + "'에 KeyManager가 구성되어 있지 않습니다.");
        }
        if (kms[0] instanceof X509ExtendedKeyManager originalKm) {
            return new X509ExtendedKeyManager() {
                @Override
                public String[] getServerAliases(String keyType, Principal[] issuers) {
                    String[] aliases = originalKm.getServerAliases(keyType, issuers);
                    return (aliases != null && aliases.length > 0) ? aliases : new String[]{"ssl"};
                }

                @Override
                public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                    String alias = originalKm.chooseServerAlias(keyType, issuers, socket);
                    return (alias != null) ? alias : "ssl";
                }

                @Override
                public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                    String alias = originalKm.chooseEngineServerAlias(keyType, issuers, engine);
                    return (alias != null) ? alias : "ssl";
                }

                @Override
                public String[] getClientAliases(String keyType, Principal[] issuers) {
                    return originalKm.getClientAliases(keyType, issuers);
                }

                @Override
                public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                    return originalKm.chooseClientAlias(keyType, issuers, socket);
                }

                @Override
                public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                    return originalKm.chooseEngineClientAlias(keyType, issuers, engine);
                }

                @Override
                public X509Certificate[] getCertificateChain(String alias) {
                    return originalKm.getCertificateChain(alias);
                }

                @Override
                public PrivateKey getPrivateKey(String alias) {
                    return originalKm.getPrivateKey(alias);
                }
            };
        }
        return kms[0];
    }

    private void initApplicationEntity() {
        ae = new ApplicationEntity(serverAet);

        // 허용된 AETitle 이외의 연결 시도를 dcm4che 레벨에서 자동 Reject(거부) 처리
        ae.setAcceptedCallingAETitles(allowedAETitles);

        // 수신 가능한 SOP Class 및 압축 포맷(Transfer Syntax) 권한 부여
        ae.addTransferCapability(getTransferCapability());

        // 컴포넌트 종속성 연결
        device.addConnection(conn);
        device.addApplicationEntity(ae);
        ae.addConnection(conn);
    }

    private void registerDicomServices() {
        DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();

        // 지원할 DICOM 명령어 등록
        serviceRegistry.addDicomService(new BasicCEchoSCP()); // C-ECHO (네트워크 핑 테스트)
        serviceRegistry.addDicomService(new CustomCStoreSCP(dicomCStoreService));

        ae.setDimseRQHandler(serviceRegistry);
    }

    private void initThreadPools() {
        // 1. Worker Thread Pool (실제 수신 처리 전담)
        // 병렬 수신을 위해 트래픽에 따라 스레드가 유동적으로 늘어남 (최대 maxThreads 제한으로 OOM 방어)
        executorService = new ThreadPoolExecutor(
                10, Math.max(10, maxThreads), 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>() // 큐에 대기시키지 않고 스레드 초과 시 즉시 Reject (방어 기제)
        ) {
            @Override
            public void execute(Runnable command) {
                super.execute(() -> {
                    // W3C 표준 규격에 맞는 32자리 Hex 문자열을 수동으로 생성 (dcm4che 내부 로그 추적용)
                    String connectionTraceId = UUID.randomUUID().toString().replace("-", "");
                    MDC.put("trace.id", connectionTraceId);
                    
                    try {
                        command.run();
                    } finally {
                        MDC.clear();
                    }
                });
            }
        };

        // 2. Timer Thread Pool (타임아웃 감시 전담)
        // Idle 상태나 응답 지연을 감시하며, 동기화 꼬임을 막기 위해 단일 스레드로 구성
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        device.setExecutor(executorService);
        device.setScheduledExecutor(scheduledExecutorService);
    }

    private static @NonNull TransferCapability getTransferCapability() {
        String[] transferSyntaxes = {
                UID.ImplicitVRLittleEndian,   // 기본 비압축 1
                UID.ExplicitVRLittleEndian,   // 기본 비압축 2
                UID.ExplicitVRBigEndian,      // 기본 비압축 3
                UID.JPEGLossless,             // 무손실 압축
                UID.JPEGLosslessSV1,
                UID.JPEGLSLossless,
                UID.JPEG2000Lossless
        };

        return new TransferCapability(
                null,
                "*",
                TransferCapability.Role.SCP,
                transferSyntaxes
        );
    }

    /**
     * 실제 C-STORE 요청(파일 수신)을 처리하는 내부 클래스
     */
    private static class CustomCStoreSCP extends BasicCStoreSCP {
        private final DicomCStoreService cStoreService;

        public CustomCStoreSCP(DicomCStoreService cStoreService) {
            super("*");
            this.cStoreService = cStoreService;
        }

        @Override
        protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data, Attributes rsp) throws IOException {
            cStoreService.store(as, pc, rq, data, rsp);
        }
    }
}
