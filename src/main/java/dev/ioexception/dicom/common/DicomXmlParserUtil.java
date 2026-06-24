package dev.ioexception.dicom.common;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;

public class DicomXmlParserUtil {

	public static String extractSuccessInfo(String xmlResponse) {
		if (xmlResponse == null || xmlResponse.isBlank()) {
			return "응답 내용이 없습니다.";
		}

		try {
			// 1. XML Document 파서 준비
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// XML에 xmlns(네임스페이스)가 있기 때문에 true로 설정하는 것이 안전
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new InputSource(new StringReader(xmlResponse)));

			// 2. XPath 준비
			XPath xPath = XPathFactory.newInstance().newXPath();

			// 3. XPath 문법을 이용해 원하는 데이터만 빼오기
			// 해석: 태그 이름이 DicomAttribute이고, keyword 속성이 'SOPInstanceUID'인 태그 아래의 Value 태그의 텍스트
			String instanceUidExpr = "//*[local-name()='DicomAttribute' and @keyword='SOPInstanceUID']/*[local-name()='Value']/text()";
			String retrieveUrlExpr = "//*[local-name()='DicomAttribute' and @keyword='RetrieveURL']/*[local-name()='Value']/text()";

			String instanceUid = (String) xPath.evaluate(instanceUidExpr, document, XPathConstants.STRING);
			String retrieveUrl = (String) xPath.evaluate(retrieveUrlExpr, document, XPathConstants.STRING);

			// 4. 결과 문자열 조합
			if (instanceUid != null && !instanceUid.isBlank()) {
				return String.format("SOPInstanceUID: %s, URL: %s", instanceUid.trim(), retrieveUrl != null ? retrieveUrl.trim() : "없음");
			}

			return "XML 파싱 완료 (필요한 UID를 찾지 못함)";
		} catch (Exception e) {
			// 파싱 중 에러가 발생하면, 무시하고 원본 XML 자체를 그냥 앞부분만 잘라서 반환
			return "원시 응답: " + xmlResponse.substring(0, Math.min(xmlResponse.length(), 100)) + "...";
		}
	}
}
