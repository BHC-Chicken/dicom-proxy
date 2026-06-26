package dev.ioexception.dicom.aop;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Scope;
import co.elastic.apm.api.Transaction;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.dcm4che3.net.Association;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class DicomMdcAspect {

	// @DicomMdcLog 어노테이션이 붙은 메서드 주변(Around)에서 실행
	@Around("@annotation(dev.ioexception.dicom.aop.DicomMdcLog)")
	public Object handleMdcLogging(ProceedingJoinPoint joinPoint) throws Throwable {
		
		String existingTraceId = MDC.get("trace.id");
		Transaction transaction;

		if (existingTraceId != null && existingTraceId.length() == 32) {
			// ThreadPool에서 만들어둔 trace.id가 있으면 APM 트랜잭션과 강제 연결 (Distributed Tracing)
			// 형식: 00-{traceId}-{spanId}-01
			String fakeSpanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
			String traceparent = "00-" + existingTraceId + "-" + fakeSpanId + "-01";
			
			transaction = ElasticApm.startTransactionWithRemoteParent(headerName -> {
				if ("traceparent".equalsIgnoreCase(headerName)) {
					return traceparent;
				}
				return null;
			});
		} else {
			// 없으면 일반 트랜잭션 시작
			transaction = ElasticApm.startTransaction();
		}

		transaction.setName("DICOM C-STORE");
		transaction.setType("request");

		String traceId = transaction.getTraceId();
		if (!traceId.isEmpty()) {
			MDC.put("trace.id", traceId);
		} else if (existingTraceId != null) {
			MDC.put("trace.id", existingTraceId);
		} else {
			// fallback (APM 에이전트가 안 붙었을 경우 대비)
			traceId = UUID.randomUUID().toString().replace("-", "");
			MDC.put("trace.id", traceId);
		}

		String clientIp = null;
		String callingAet = null;

		// 메서드 파라미터들을 뒤져서 Association 객체가 있으면 IP와 AET 추출
		for (Object arg : joinPoint.getArgs()) {
			if (arg instanceof Association) {
				Association as = (Association) arg;
				if (as.getSocket() != null && as.getSocket().getInetAddress() != null) {
					clientIp = as.getSocket().getInetAddress().getHostAddress();
					MDC.put("client.ip", clientIp);
				}

				callingAet = as.getCallingAET();
				MDC.put("calling.aet", callingAet);
				break;
			}
		}

		// APM 레이블 추가
		transaction.setLabel("trace.id", traceId);

		if (clientIp != null) {
			transaction.setLabel("client.ip", clientIp);
		}

		if (callingAet != null) {
			transaction.setLabel("calling.aet", callingAet);
		}

		try (Scope scope = transaction.activate()) {
			// 실제 비즈니스 로직(store 등) 실행
			return joinPoint.proceed();
		} catch (Throwable e) {
			transaction.captureException(e);
			throw e;
		} finally {
			transaction.end();
		}
	}
}
