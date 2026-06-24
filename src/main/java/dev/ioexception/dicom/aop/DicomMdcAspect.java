package dev.ioexception.dicom.aop;

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
		String traceId = UUID.randomUUID().toString().replace("-", "");
		MDC.put("trace.id", traceId);

		// 메서드 파라미터들을 뒤져서 Association 객체가 있으면 IP와 AET 추출
		for (Object arg : joinPoint.getArgs()) {
			if (arg instanceof Association) {
				Association as = (Association) arg;
				if (as.getSocket() != null && as.getSocket().getInetAddress() != null) {
					MDC.put("client.ip", as.getSocket().getInetAddress().getHostAddress());
				}
				MDC.put("calling.aet", as.getCallingAET());
				break;
			}
		}

		try {
			// 실제 비즈니스 로직(store 등) 실행
			return joinPoint.proceed();
		} finally {
			// 실행이 끝나면 무조건 MDC 초기화
			MDC.clear();
		}
	}
}
