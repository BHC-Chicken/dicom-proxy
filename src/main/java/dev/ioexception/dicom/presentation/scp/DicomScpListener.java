package dev.ioexception.dicom.presentation.scp;

import dev.ioexception.dicom.service.DicomStorageService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DicomScpListener {
    private final DicomStorageService dicomStorageService;

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

    @PostConstruct
    public void startServer() throws Exception {
        log.info("DICOM SCP(서버) 초기화를 시작합니다...");

        initDeviceAndConnection();
        initApplicationEntity();
        registerDicomServices();
        initThreadPools();

        device.bindConnections();
        log.info("DICOM SCP(서버)가 포트 {}에서 구동되었습니다. (AETitle: {})", serverPort, serverAet);
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
        serviceRegistry.addDicomService(new CustomCStoreSCP(dicomStorageService));

        ae.setDimseRQHandler(serviceRegistry);
    }

    private void initThreadPools() {
        // 1. Worker Thread Pool (실제 수신 처리 전담)
        // 병렬 수신을 위해 트래픽에 따라 스레드가 유동적으로 늘어남 (최대 maxThreads 제한으로 OOM 방어)
        executorService = new ThreadPoolExecutor(
                10, maxThreads, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>() // 큐에 대기시키지 않고 스레드 초과 시 즉시 Reject (방어 기제)
        );

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
        private final DicomStorageService storageService;

        public CustomCStoreSCP(DicomStorageService storageService) {
            super("*");
            this.storageService = storageService;
        }

        @Override
        protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data, Attributes rsp) throws IOException {
            log.info("--- [C-STORE 파일 수신 시작] SCU: {} ---", as.getCallingAET());
            Path tempFilePath = null;

            try {
                // 1. 스트림을 임시 파일로 저장 (네트워크 I/O)
                tempFilePath = Files.createTempFile("dicom_temp_", ".dcm");
                Files.copy(data, tempFilePath, StandardCopyOption.REPLACE_EXISTING);

                // 2. 비즈니스 로직(파싱, 경로 이동)은 Service에게 위임
                storageService.processAndSaveDicom(tempFilePath, rq);

            } catch (Exception e) {
                log.error("DICOM 처리 실패: {}", e.getMessage(), e);
                throw new IOException("C-STORE 프로세스 실패", e);
            } finally {
                // 3. 에러가 나거나 이동에 실패해 임시 파일이 남아있다면 삭제 보장
                if (tempFilePath != null && Files.exists(tempFilePath)) {
                    Files.deleteIfExists(tempFilePath);
                }
            }
            log.info("--- [C-STORE 파일 수신 정상 종료] ---");
        }
    }
}
